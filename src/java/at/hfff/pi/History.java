/* $Id: History.java,v 1.9 2019/11/11 23:22:40 horst Exp $ */
package at.hfff.pi;

import static at.hfff.pi.PiHive.LOG;

import at.hfff.pi.ws.PiEndpoint;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Undecided if extending ArrayDeque or using local var and delegating
 * some methods (clear(), size(), stream()) is better
 * @author horst
 */
public class History extends ArrayDeque<StampedNV> {

  private final Mailer mailer = new Mailer();
  private PrintWriter logger;   // autoflush?
 
  protected static final long HSIZE = 7*24*3600*1000;  // history expires after 1 week
  
  public void setLogWriter(PrintWriter logger) {
    this.logger = logger;
  }
  
  /**
   * 
   * @param he 
   */
  @Override
  public void addLast(StampedNV he) { // add() is equivalent to addLast()
    // trim to period to keep
    try {
      while (!isEmpty() && System.currentTimeMillis() - ((StampedNV) getFirst()).pTime > HSIZE) {
        synchronized (this) {
          removeFirst();
        }
      }
    } catch (NoSuchElementException e) {
      LOG.log(Level.INFO, "unexpected", e);
    }
    synchronized (this) {
      super.addLast(he);
    }
    if (logger != null) {
      logger.println(he);
    }
    deliver(he);

  }
  
  public void close() {
    if (logger != null)
      logger.close();
  }
  
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    synchronized (this) {
      forEach((snv) -> {
        sb.append(snv).append('\n');
      });
    }
    return sb.toString();
  }
  
  // keep references to websocket SendHandler to allow broadcasting of change notifications
  protected void addClient(String id, PiEndpoint.OutputFeeder receiver) {
    mailer.put(id, receiver);
  }

  protected void removeClient(String id) {
    mailer.remove(id);
  }

  protected void deliver(StampedNV he) {
    mailer.deliver(he);
  }

  class Mailer extends ConcurrentHashMap<String, PiEndpoint.OutputFeeder> {

    public void deliver(StampedNV nv) {
      // maximum 2 threads sending?
      forEachEntry(2, (Object t) -> {
        Map.Entry me = (Map.Entry) t;
        PiEndpoint.OutputFeeder of = (PiEndpoint.OutputFeeder) me.getValue();
        //TODO: remove entries from Mailer where delivery fails 
        LOG.log(Level.FINE, "Sending {0} to {1}", new Object[]{nv, me.getKey()});
        of.send(nv.toString());
      });
    }
  }

}
