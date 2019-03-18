/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.network.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.codahale.metrics.MetricSet;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.network.TransportContext;
import org.apache.spark.network.server.TransportChannelHandler;
import org.apache.spark.network.util.*;

/**
 * Factory for creating {@link TransportClient}s by using createClient.
 *
 * The factory maintains a connection pool to other hosts and should return the same
 * TransportClient for the same remote host. It also shares a single worker thread pool for
 * all TransportClients.
 *
 * TransportClients will be reused whenever possible. Prior to completing the creation of a new
 * TransportClient, all given {@link TransportClientBootstrap}s will be run.
 */

/**
 * RPC client Factory, TransportClient的工厂类
 * 维护了与其他主机的连接池，为同一个主机返回相同的TransportClient，
 */
public class TransportClientFactory implements Closeable {

  /** A simple data structure to track the pool of clients between two peer nodes. */
  // ClientPool 由TransportClient数组组成，其中为每个TransportClient分配了一个lock，降低对锁的竞争，提高并发度
  private static class ClientPool {
    TransportClient[] clients;
    Object[] locks;

    ClientPool(int size) {
      clients = new TransportClient[size];
      locks = new Object[size];
      for (int i = 0; i < size; i++) {
        locks[i] = new Object();
      }
    }
  }

  private static final Logger logger = LoggerFactory.getLogger(TransportClientFactory.class);

  // 传输的上下文
  private final TransportContext context;
  // 配置信息conf
  private final TransportConf conf;
  // 客户端引导程序TransportClientBootstrap列表
  private final List<TransportClientBootstrap> clientBootstraps;
  // 为每个socket地址存放一个连接池ClientPool
  private final ConcurrentHashMap<SocketAddress, ClientPool> connectionPool;

  /** Random number generator for picking connections between peers. */
  // socket地址对应对连接池clientPool中缓存对TransportClient随机，每个连接均衡负载
  private final Random rand;
  // 从TransportConf获取key为 spark + module + io.num-numConnectionsPerPeer
  // 指定对等节点件间的连接数
  private final int numConnectionsPerPeer;

  // 客户端channel中被创建使用的类，可根据IO模式匹配，IO模式支持Nio和epoll
  private final Class<? extends Channel> socketChannelClass;
  // netty
  private EventLoopGroup workerGroup;
  // 汇集ByteBuf但对本地缓存禁用的分配器
  private final PooledByteBufAllocator pooledAllocator;
  private final NettyMemoryMetrics metrics;

  // 实现

  /**
   * TransportClientBootstrap列表是在TransportClient上执行的客户端引导程序，主要对连接建立一下初始的准备（加密，验证等）
   * @param context
   * @param clientBootstraps
   */
  public TransportClientFactory(
      TransportContext context,
      List<TransportClientBootstrap> clientBootstraps) {
    this.context = Preconditions.checkNotNull(context);
    this.conf = context.getConf();
    this.clientBootstraps = Lists.newArrayList(Preconditions.checkNotNull(clientBootstraps));
    this.connectionPool = new ConcurrentHashMap<>();
    this.numConnectionsPerPeer = conf.numConnectionsPerPeer();
    this.rand = new Random();

    // IO模式，支持NIO和epoll
    IOMode ioMode = IOMode.valueOf(conf.ioMode());
    this.socketChannelClass = NettyUtils.getClientChannelClass(ioMode);
    this.workerGroup = NettyUtils.createEventLoop(
        ioMode,
        conf.clientThreads(),
        conf.getModuleName() + "-client");
    if (conf.sharedByteBufAllocators()) {
      this.pooledAllocator = NettyUtils.getSharedPooledByteBufAllocator(
          conf.preferDirectBufsForSharedByteBufAllocators(), false /* allowCache */);
    } else {
      this.pooledAllocator = NettyUtils.createPooledByteBufAllocator(
          conf.preferDirectBufs(), false /* allowCache */, conf.clientThreads());
    }
    this.metrics = new NettyMemoryMetrics(
      this.pooledAllocator, conf.getModuleName() + "-client", conf);
  }

  public MetricSet getAllMetrics() {
    return metrics;
  }

  /**
   * Create a {@link TransportClient} connecting to the given remote host / port.
   *
   * We maintains an array of clients (size determined by spark.shuffle.io.numConnectionsPerPeer)
   * and randomly picks one to use. If no client was previously created in the randomly selected
   * spot, this function creates a new client and places it there.
   *
   * Prior to the creation of a new TransportClient, we will execute all
   * {@link TransportClientBootstrap}s that are registered with this factory.
   *
   * This blocks until a connection is successfully established and fully bootstrapped.
   *
   * Concurrency: This method is safe to call from multiple threads.
   */

  // 创建RPC客户端TransportClient
  public TransportClient createClient(String remoteHost, int remotePort)
      throws IOException, InterruptedException {
    // Get connection from the connection pool first.
    // If it is not found or not active, create a new one.
    // Use unresolved address here to avoid DNS resolution each time we creates a client.
    // 1. 创建InetSocketAddress， 可以在缓存中先找，避免了DNS解析
    final InetSocketAddress unresolvedAddress =
      InetSocketAddress.createUnresolved(remoteHost, remotePort);

    // Create the ClientPool if we don't have it yet.
    // 2. 找到ClientPool，没有就新建放入connectionPool中
    ClientPool clientPool = connectionPool.get(unresolvedAddress);
    if (clientPool == null) {
      connectionPool.putIfAbsent(unresolvedAddress, new ClientPool(numConnectionsPerPeer));
      clientPool = connectionPool.get(unresolvedAddress);
    }

    // 3. 随机选择一个TransportClient
    int clientIndex = rand.nextInt(numConnectionsPerPeer);
    TransportClient cachedClient = clientPool.clients[clientIndex];

    // 4. 如果缓存中的TransportClient不可用或没激活
    if (cachedClient != null && cachedClient.isActive()) {
      // Make sure that the channel will not timeout by updating the last use time of the
      // handler. Then check that the client is still alive, in case it timed out before
      // this code was able to update things.
      // 更新TransportClient的channel的最后一次时间，确保channel没有超时
      TransportChannelHandler handler = cachedClient.getChannel().pipeline()
        .get(TransportChannelHandler.class);
      synchronized (handler) {
        handler.getResponseHandler().updateTimeOfLastRequest();
      }

      // TransportClient可用直接返回
      if (cachedClient.isActive()) {
        logger.trace("Returning cached connection to {}: {}",
          cachedClient.getSocketAddress(), cachedClient);
        return cachedClient;
      }
    }

    // If we reach here, we don't have an existing connection open. Let's create a new one.
    // Multiple threads might race here to create new connections. Keep only one of them active.
    // 5。 没有可用的TransportClient，直接域名解析然。
    // 因为没有加锁，所以可能很多线程都会执行到这一步
    final long preResolveHost = System.nanoTime();
    final InetSocketAddress resolvedAddress = new InetSocketAddress(remoteHost, remotePort);
    final long hostResolveTimeMs = (System.nanoTime() - preResolveHost) / 1000000;
    if (hostResolveTimeMs > 2000) {
      logger.warn("DNS resolution for {} took {} ms", resolvedAddress, hostResolveTimeMs);
    } else {
      logger.trace("DNS resolution for {} took {} ms", resolvedAddress, hostResolveTimeMs);
    }

    // 6. 加锁创建TransportClient
    synchronized (clientPool.locks[clientIndex]) {
      cachedClient = clientPool.clients[clientIndex];

      if (cachedClient != null) {
        if (cachedClient.isActive()) {
          logger.trace("Returning cached connection to {}: {}", resolvedAddress, cachedClient);
          return cachedClient;
        } else {
          logger.info("Found inactive connection to {}, creating a new one.", resolvedAddress);
        }
      }
      clientPool.clients[clientIndex] = createClient(resolvedAddress);
      return clientPool.clients[clientIndex];
    }
  }

  /**
   * Create a completely new {@link TransportClient} to the given remote host / port.
   * This connection is not pooled.
   *
   * As with {@link #createClient(String, int)}, this method is blocking.
   */
  public TransportClient createUnmanagedClient(String remoteHost, int remotePort)
      throws IOException, InterruptedException {
    final InetSocketAddress address = new InetSocketAddress(remoteHost, remotePort);
    return createClient(address);
  }

  /** Create a completely new {@link TransportClient} to the remote address. */
  // 真正的调用此函数，创建TransportClient
  private TransportClient createClient(InetSocketAddress address)
      throws IOException, InterruptedException {
    logger.debug("Creating new connection to {}", address);

    // 构建根引导程序BootStrap并对其进行配置
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.group(workerGroup)
      .channel(socketChannelClass)
      // Disable Nagle's Algorithm since we don't want packets to wait
      .option(ChannelOption.TCP_NODELAY, true)
      .option(ChannelOption.SO_KEEPALIVE, true)
      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, conf.connectionTimeoutMs())
      .option(ChannelOption.ALLOCATOR, pooledAllocator);

    if (conf.receiveBuf() > 0) {
      bootstrap.option(ChannelOption.SO_RCVBUF, conf.receiveBuf());
    }

    if (conf.sendBuf() > 0) {
      bootstrap.option(ChannelOption.SO_SNDBUF, conf.sendBuf());
    }

    final AtomicReference<TransportClient> clientRef = new AtomicReference<>();
    final AtomicReference<Channel> channelRef = new AtomicReference<>();

    // 为根引导程序设置管道初始化回调函数
    bootstrap.handler(new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(SocketChannel ch) {
        // 调用TransportContext中的initializePipeline初始化channel的pipe
        TransportChannelHandler clientHandler = context.initializePipeline(ch);
        clientRef.set(clientHandler.getClient());
        channelRef.set(ch);
      }
    });

    // Connect to the remote server
    // 根引导程序连接远程服务器
    long preConnect = System.nanoTime();
    ChannelFuture cf = bootstrap.connect(address);
    if (!cf.await(conf.connectionTimeoutMs())) {
      throw new IOException(
        String.format("Connecting to %s timed out (%s ms)", address, conf.connectionTimeoutMs()));
    } else if (cf.cause() != null) {
      throw new IOException(String.format("Failed to connect to %s", address), cf.cause());
    }

    // 设置引用
    TransportClient client = clientRef.get();
    Channel channel = channelRef.get();
    assert client != null : "Channel future completed successfully with null client";

    // Execute any client bootstraps synchronously before marking the Client as successful.
    long preBootstrap = System.nanoTime();
    logger.debug("Connection to {} successful, running bootstraps...", address);
    try {
      for (TransportClientBootstrap clientBootstrap : clientBootstraps) {
        // 给TransportClient设置客户端引导程序
        clientBootstrap.doBootstrap(client, channel);
      }
    } catch (Exception e) { // catch non-RuntimeExceptions too as bootstrap may be written in Scala
      long bootstrapTimeMs = (System.nanoTime() - preBootstrap) / 1000000;
      logger.error("Exception while bootstrapping client after " + bootstrapTimeMs + " ms", e);
      client.close();
      throw Throwables.propagate(e);
    }
    long postBootstrap = System.nanoTime();

    logger.info("Successfully created connection to {} after {} ms ({} ms spent in bootstraps)",
      address, (postBootstrap - preConnect) / 1000000, (postBootstrap - preBootstrap) / 1000000);

    return client;
  }

  /** Close all connections in the connection pool, and shutdown the worker thread pool. */
  @Override
  public void close() {
    // Go through all clients and close them if they are active.
    for (ClientPool clientPool : connectionPool.values()) {
      for (int i = 0; i < clientPool.clients.length; i++) {
        TransportClient client = clientPool.clients[i];
        if (client != null) {
          clientPool.clients[i] = null;
          JavaUtils.closeQuietly(client);
        }
      }
    }
    connectionPool.clear();

    if (workerGroup != null) {
      workerGroup.shutdownGracefully();
      workerGroup = null;
    }
  }
}
