/*
 *Copyright 2014 DDPush
 *Author: AndyKwok(in English) GuoZhengzhu(in Chinese)
 *Email: ddpush@126.com
 *

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

 */
package org.ddpush.im.v1.node.pushlistener;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.SocketChannel;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.ddpush.im.util.PropertyUtil;
import org.ddpush.im.util.StringUtil;

public class NIOPushListener implements Runnable {
	private static int sockTimout = 1000 * PropertyUtil
			.getPropertyInt("PUSH_LISTENER_SOCKET_TIMEOUT");
	private static int port = PropertyUtil.getPropertyInt("PUSH_LISTENER_PORT");
	private static int BACK_LOG = PropertyUtil.getPropertyInt("BACK_LOG");

	private boolean stoped = false;

	ServerSocketChannel channel = null;
	ServerBootstrap serverBootstarp = null;
	EventLoopGroup bossGroup = null;
	EventLoopGroup workerGroup = null;
	private Selector selector = null;

	private ExecutorService executor;

	private int minThreads = PropertyUtil
			.getPropertyInt("PUSH_LISTENER_MIN_THREAD");
	private int maxThreads = PropertyUtil
			.getPropertyInt("PUSH_LISTENER_MAX_THREAD");

	protected ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<Runnable>();

	public void init() throws Exception {
		initExecutor();
		initChannel();

	}

	public void initChannel() throws Exception {
		bossGroup = new NioEventLoopGroup();
		workerGroup = new NioEventLoopGroup();
		serverBootstarp = new ServerBootstrap().group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.option(ChannelOption.SO_BACKLOG, BACK_LOG)
				.childHandler(new ChannelInitializer<SocketChannel>() {

					@Override
					protected void initChannel(SocketChannel ch)
							throws Exception {

					}
				});

		serverBootstarp.bind(port);

		System.out.println("NIO TCP Push Listener nio provider: "
				+ serverBootstarp.getClass().getCanonicalName());
	}

	public void initExecutor() throws Exception {
		if (executor == null) {
			executor = new ThreadPoolExecutor(minThreads, maxThreads, 30,
					TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		}
	}

	public void addEvent(Runnable event) {
		if (selector == null) {
			return;
		}

		events.add(event);

		if (stoped == false && selector != null) {
			selector.wakeup();
		}

	}

	@Override
	public void run() {
		try {
			init();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		System.out.println("push listener port:" + this.port);

		while (!stoped && selector != null) {
			try {
				handleEvent();
				handleTimeout();
				handleChannel();
			} catch (java.nio.channels.ClosedSelectorException cse) {
				//
			} catch (java.nio.channels.CancelledKeyException nx) {
				//
			} catch (Exception e) {
				e.printStackTrace();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}

		closeSelector();

	}

	public void stop() {
		this.stoped = true;
		if (this.selector != null) {
			try {
				selector.wakeup();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		new Thread(new Runnable() {

			@Override
			public void run() {
				stopExecutor();
				closeSelector();
			}

		}).start();
	}

	private void stopExecutor() {
		try {
			if (executor != null)
				executor.shutdownNow();// ignore left overs
		} catch (Exception e) {
			e.printStackTrace();
		}
		executor = null;
	}

	private void closeSelector() {
		bossGroup.shutdownGracefully();
		workerGroup.shutdownGracefully();
	}

	private void handleEvent() {
		Runnable r = null;
		while (true) {
			r = events.poll();
			if (r == null) {
				// no events
				return;
			}
			try {
				r.run();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private void handleTimeout() {
		Selector tmpsel = selector;
		Set keys = (stoped == false && tmpsel != null) ? tmpsel.keys() : null;
		if (keys == null) {
			return;
		}
		Iterator it = keys.iterator();
		long now = System.currentTimeMillis();
		// cancel timeout and no interestOps key,close socket and channel
		while (it.hasNext()) {
			SelectionKey key = (SelectionKey) it.next();
			if (key.channel() instanceof ServerSocketChannel) {
				continue;
			}
			if (key.isValid() == false) {
				continue;
			}
			try {
				// if(key.interestOps() != 0){
				// continue;
				// }
				PushTask task = (PushTask) key.attachment();
				if (task == null) {
					cancelKey(key);
					continue;
				}
				if (task.isWritePending() == false
						&& now - task.getLastActive() > sockTimout) {
					cancelKey(key);
				}
			} catch (CancelledKeyException e) {
				cancelKey(key);
			}
		}
	}

	private void handleChannel() throws Exception {
		if (selector.select() == 0) {
			try {
				Thread.sleep(1);
			} catch (Exception e) {

			}
			return;
		}

		Iterator<SelectionKey> it = selector.selectedKeys().iterator();
		while (it.hasNext()) {
			SelectionKey key = it.next();
			it.remove();
			// Is a new connection coming in?
			if (key.isAcceptable()) {
				try {
					ServerSocketChannel server = (ServerSocketChannel) key
							.channel();
					SocketChannel channel = server.accept();
					channel.configureBlocking(false);
					channel.socket().setSoTimeout(sockTimout);
					// channel.socket().setReceiveBufferSize(1024);
					// channel.socket().setSendBufferSize(1024);
					PushTask task = new PushTask(this, channel);
					channel.register(selector, SelectionKey.OP_READ, task);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if (key.isReadable() || key.isWritable()) {
				try {
					PushTask task = (PushTask) key.attachment();
					if (task == null) {// this should never happen
						cancelKey(key);
						continue;
					}
					task.setKey(key);
					executor.execute(task);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

		}
	}

	public static void cancelKey(SelectionKey key) {
		if (key == null)
			return;

		key.cancel();
		try {
			((SocketChannel) key.channel()).socket().close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			key.channel().close();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
		class test implements Runnable {
			AtomicInteger cnt;

			public test(AtomicInteger cnt) {
				this.cnt = cnt;
			}

			public void run() {
				try {
					Socket s = new Socket(
							"localhost",
							PropertyUtil
									.getPropertyInt("Constant.PUSH_LISTENER_PORT"));
					s.setSoTimeout(0);
					InputStream in = s.getInputStream();
					OutputStream out = s.getOutputStream();

					// for(int i = 600000; i< 700000; i++){
					// while(true){
					int key = cnt.addAndGet(1);
					if (key > 10000) {
						return;
					}
					out.write(1);
					out.write(1);
					out.write(16);
					out.write(StringUtil.md5Byte("" + key));
					out.write(0);
					out.write(0);
					out.flush();

					byte[] b = new byte[1];
					int read = in.read(b);
					System.out.println(b[0]);
					// }
					// while(true){
					// int read = in.read(b);
					// System.out.println(b[0]);
					// if(read < 0){
					// break;
					// }
					// }
					s.close();
					System.out.println("bye~~");
					// long time = din.readLong();
					// System.out.println("time:"+time);
					// din.readLong();

				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		Thread[] worker = new Thread[10000];
		AtomicInteger cnt = new AtomicInteger(-1);
		for (int i = 0; i < worker.length; i++) {
			Thread t = new Thread(new test(cnt));
			worker[i] = t;
		}
		for (int i = 0; i < worker.length; i++) {
			worker[i].start();
			try {
				Thread.sleep(2);
			} catch (Exception e) {
			}
		}

		for (int i = 0; i < worker.length; i++) {
			try {
				worker[i].join();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		System.out.println("done~~~~~~~~~~~~~");
	}
}
