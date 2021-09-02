package at.hfff.pi;

import java.text.SimpleDateFormat;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author horst
 */
public class DelayableOrder implements Delayed {
  // constants for timing types
  public static final int PUBLISH = 1;   // publish results
  public static final int ARCHIVE = 1<<2;   // archive results
  
  public static final int WSTRIGGER = 1<<5;  // query Open Weather Map for local weather
  
  // currently based on HBDELAY (=1 sec), no need for extra timing
  public static final int W1QUERY = 1<<6;   // Schedule W1Sensors processing
  public static final int HXQUERY = 1<<7;   // trigger weight measurement

  private int type;  // timer type
  private long delay; // in milliseconds from now till expiration
  private boolean canceled = false; // set obsolete (instead of removing from queue)
  private final long start = System.currentTimeMillis();
  
  private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS");
  
  /**
   * order for immediate action
   * @param type 
   */
  public DelayableOrder(int type) {
    this(type, 0);
  }
  
  /**
   * feed into main loop with default "next" 
   * @param type timer type (bit position)
   * @param delay delay after start
   */
  public DelayableOrder(int type, long delay) {
    this.type = type;
    this.delay = delay;
  }
  
  public Integer getType() {
    return type;
  }

  public String getName() {
    switch (type) {
   
      case WSTRIGGER: return "WSTRIGGER";
      case W1QUERY: return "W1QUERY";
      case HXQUERY: return "HXQUERY";
      default: return "UNDEFINED";
    }
  }
  /**
   * 
   * @return true if order was created with initial delay 
   */
  public boolean isDelayed() {
    return delay > 0;
  }
  
  public void cancel() {
    canceled = true;
  }
  
  public boolean isCanceled() {
    return canceled;
  }
   
  private long getCurrentDelay() {
    return start + delay - System.currentTimeMillis();
  }
  
  @Override
  public long getDelay(TimeUnit unit) {
    return unit.convert(getCurrentDelay(), TimeUnit.MILLISECONDS);
  }

  @Override
  public int compareTo(Delayed o) {
    return (int) (getCurrentDelay() -o.getDelay(TimeUnit.MILLISECONDS));
  }
 
  @Override
  public String toString() {
    return "{type=" + getName() + " canceled=" + canceled +  " start=" + SDF.format(start) + " delay=" + getCurrentDelay() + "}";
  }
}
