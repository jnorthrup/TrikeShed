package rxf.server;

import rxf.server.PreRead;
import rxf.server.driver.RxfBootstrap;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static one.xio.HttpMethod.UTF8;

/**
 * this visitor shovels data from the outward selector to the inward selector, and vice versa.  once the headers are
 * sent inward the only state monitored is when one side of the connections close.
 */
public class HttpPipeVisitor extends one.xio.AsioVisitor.Impl implements PreRead {
  public static final boolean PROXY_DEBUG =
      "true".equals(RxfBootstrap.getVar("PROXY_DEBUG", String.valueOf(false)));
  final private java.nio.ByteBuffer[] b;
  protected String name;
  //  public AtomicInteger remaining;
  java.nio.channels.SelectionKey otherKey;
  private boolean limit;

  public HttpPipeVisitor(String name, java.nio.channels.SelectionKey otherKey, java.nio.ByteBuffer... b) {
    this.name = name;
    this.otherKey = otherKey;
    this.b = b;
  }

  @Override
  public void onRead(java.nio.channels.SelectionKey key) throws Exception {
    java.nio.channels.SocketChannel channel = (java.nio.channels.SocketChannel) key.channel();
    if (otherKey.isValid()) {
      int read = channel.read(getInBuffer());
      if (read == -1) /*key.cancel();*/
      {
        channel.shutdownInput();
        key.interestOps(OP_WRITE);
        channel.write(java.nio.ByteBuffer.allocate(0));
      } else {
        //if buffer fills up, stop the read option for a bit
        otherKey.interestOps(OP_READ | OP_WRITE);
        channel.write(java.nio.ByteBuffer.allocate(0));
      }
    } else {
      key.cancel();
    }
  }

  @Override
  public void onWrite(java.nio.channels.SelectionKey key) throws Exception {
    java.nio.channels.SocketChannel channel = (java.nio.channels.SocketChannel) key.channel();
    java.nio.ByteBuffer flip = (java.nio.ByteBuffer) getOutBuffer().flip();
    if (PROXY_DEBUG) {
      java.nio.CharBuffer decode = UTF8.decode(flip.duplicate());
      System.err.println("writing to " + name + ": " + decode + "-");
    }
    int write = channel.write(flip);

    if (-1 == write || isLimit() /*&& null != remaining && 0 == remaining.get()*/) {
      key.cancel();
    } else {
      //      if (isLimit() /*&& null != remaining*/) {
      //        /*this.remaining.getAndAdd(-write);*//*
      //        if (1 > remaining.get()) */{
      //          key.channel().close();
      //          otherKey.channel().close();
      //          return;
      //        }
      //      }
      key.interestOps(OP_READ | OP_WRITE);// (getOutBuffer().hasRemaining() ? OP_WRITE : 0));
      getOutBuffer().compact();
    }
  }

  public java.nio.ByteBuffer getInBuffer() {
    return b[0];
  }

  public java.nio.ByteBuffer getOutBuffer() {
    return b[1];
  }

  public boolean isLimit() {
    return limit;
  }

  public void setLimit(boolean limit) {
    this.limit = limit;
  }
}
