package org.tridiots.ipc;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private Object instance;
    private InetSocketAddress address;
    private ServerSocketChannel accpetChannel;
    private Selector selector;
    private volatile boolean running = true;

    public Server(Object instance, int port) throws IOException {
        this.instance = instance;
        this.address = new InetSocketAddress("localhost", port);

        this.accpetChannel = ServerSocketChannel.open();
        accpetChannel.configureBlocking(false);
        accpetChannel.socket().bind(address);

        this.selector = Selector.open();
        accpetChannel.register(selector, SelectionKey.OP_ACCEPT);

        logger.info("Server start listen to server: {}:{}", address.getHostName(), address.getPort());
    }

    private void doAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel channel = server.accept();
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ);

        logger.debug("accept a connection from {}.", channel.socket().getInetAddress());
    }

    private void doRead(SelectionKey key) throws IOException, ClassNotFoundException {
        SocketChannel channel = (SocketChannel) key.channel();
        logger.debug("read from connection from {}.", channel.socket().getInetAddress().getHostName());
        Param param = (Param) SocketObjectUtil.receiveObject(channel);

        if (param == null) {
            // the channel will always readable if we don't close
            channel.close();
            return;
        }
        Object result = call(param);

        key.attach(result);
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void doWrite(SelectionKey key) throws IOException, ClassNotFoundException {
        Object result =  key.attachment();
        if (result == null) return;

        SocketChannel channel = (SocketChannel) key.channel();
        SocketObjectUtil.sendObject(channel, result);
        key.interestOps(SelectionKey.OP_READ);

        logger.debug("write to connection {}.", channel.socket().getInetAddress().getHostName());
    }

    @Override
    public void run() {
        while (running) {
            try {
                if (selector.select() == 0) continue;

                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key  = iter.next();
                    iter.remove();

                    if (key.isAcceptable()) {
                        doAccept(key);
                    } else if (key.isReadable()) {
                        doRead(key);
                    } else if (key.isWritable()) {
                        doWrite(key);
                    }
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            } catch (ClassNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    public synchronized void join() {
        try {
            while (running) {
                wait();
            }
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void start() {
        new Thread(this).start();
    }

    public synchronized void stop() {
        this.running = false;    
        notifyAll();
    }

    private Object call(Param param) {
        try {
            Method method = instance.getClass()
                    .getMethod(param.getMethodName(), param.getParamTypes());
            return method.invoke(instance, param.getParams());
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }
}
