package at.hfff.pi;

import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.wiringpi.Gpio;
import static java.lang.Thread.sleep;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Highly difficult without using direct register access and e.g. WiringPI
 * ShiftLibrary http://wiringpi.com/reference/shift-library/ as handled
 * https://electronics.stackexchange.com/questions/320528/how-read-count-of-24-bit-adc-hx711
 * and used for
 * https://community.hiveeyes.org/t/improving-the-canonical-arduino-hx711-library-for-esp32-and-beyond/539
 * Trying to overcome failing measurements by fault dedection and nice floating mean
 * https://github.com/Poduzov/HX711-Pi4j is a too simple example
 *
 * @author horst
 */
public class HXSensor extends Sensor {

  private Optional<CompletableFuture<StampedNV>> weightData = Optional.empty();

  private final GpioPinDigitalOutput pinClk;
  private final GpioPinDigitalInput pinData;

  // for plausibility check 
  private static final double PFMIN = 0.1; // ignore raw data when plausibility is less than PFMIN
  private static final double PFSHAPE = 4.0;   // > 0 , higher value reduces noise but decreases reaction time
  private double weight = Double.NaN;  // weight (mean)
  private double psv = Double.NaN; // preceeding scaled measured value

  // channel A, gain factor 128 : 24
  // channel A, gain factor 64 : 26
  // channel B, gain factor 32 : 25
  private final int gain = 24;   // ticks for high precission  (HX711 default)

  // retry limit ( ~1 hour)
  private static final int ERRMAX = 3600;

  // logging when failCnt mod ERRLOG == 0 (Level.FINE -> ERRLOG becomes 1 (each occurence)  
  private static final int ERRLOG = 60;

  // wakeup may take a while (400 ms according sheet) but with 700 not yet low ???
  // aproaching by using multiple shorttime requests leads to less stable results
  private static final long WAITMIN = 800;
  
  /**
   * Note: In nonrealtime mode it is not possible to avoid interruptions
   * Here the attempt is made to dedect that by checking reaction.
   * If it takes longer than PULSEMAX an invalid ADC readout is assumed
   * (honeypi HX711.py does for > 60 µs), ak during shift operation no high pulse shall take longer then 60µs
   * wiringPi shiftIn is not directly accessible by pi4j user API
   * and a plausibility calculation allows to exclude values causing high slopes.
   * See esphive project where HX711.cpp uses continuous sampling multiple values
   * and allows to remove MIN and/or MAX before scaling.
   * Continous sampling might help to increase reaction time.
   * Attention: Turning loglevel to FINE will increase failure occurences
   * INFO: count=713def sv=37.378 psv=37.376 pf=0.996 weight=37.378
   * FINE: Highs:31, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30
   * FINE: Lows: 59, 53, 49, 51, 50, 50, 48, 51, 49, 50, 48, 50, 49, 50, 48, 50, 50, 50, 47, 50, 49, 49, 48, 50
  */
  private static final int PULSEMAX = 60;   // 60 µsec, measured for data pin
  // since bullseye,tomcat9 with zulu11 java minwidth of pulse required!?
  // pihive3 seems to need >= 20, if 15 result is always 0x07fffff
  private static final int PULSEMIN = 20;   

  private volatile int failCnt = 0;
  private final int[] highs = new int[gain];  // record high pulse timing for debug 
  private final int[] lows = new int[gain];   // record low pulse timing for debug
  private static final Logger LOG = PiHive.LOG;

  public HXSensor(String[] params, GpioPinDigitalOutput clk, GpioPinDigitalInput data) {
    super(params);
    // no special defaults, overridable by sensor persistence, use pihive x put sensors.cfg 
    //super.setCalibration(4.9E-5, 400.0); // experimental from prototype
    //super.setDelta(0.1);  // 100g (just a default, 50 g might be better, change in sensors.cfg
    this.pinClk = clk;
    this.pinData = data;
  }
  
  /**
   * prepare optional future
   * CAVEAT: With user level java there is no chance to ensure max 60
   * microseconds shift pulses
   * @return false if no measurement is started (e.g. due to ERRMAX exceeded)
   */
  public boolean trigger() {
    if (failCnt >= ERRMAX) {
      LOG.log(Level.WARNING, "Errorlimit exceeded");
      return false;
    }
    if (Double.isNaN(weight)) {   // new session ?
      StampedNV last = getLast();
      if (last != null)
        weight = psv = (double)(last.value);   // use that for plausibility checking
      if (!Double.isNaN(weight))
        LOG.log(Level.INFO, "Weight restored to {0}", weight);
    }
    
    weightData = Optional.of(CompletableFuture.supplyAsync(() -> {
      // will cause java.lang.IllegalThreadStateException
      //Thread.currentThread().setDaemon(true);  // to allow vm exit even if thread is running
      
      // return value, nonnull if ok
      StampedNV snv = null;

      // reduced logging when not FINE
      int logred = LOG.isLoggable(Level.FINE) ? 1 : ERRLOG;
      
      // assuming sleep state, aka HIGH 
      if (pinData.isHigh()) {
        pinClk.setState(PinState.LOW);    // return to normal mode (wakeup)

        Arrays.fill(highs, 0);   // for pulse timing check   
        Arrays.fill(lows, 0);
        
        // last chance of OS to do something else
        try {
          sleep(WAITMIN);
        } catch (InterruptedException ex) {
          LOG.log(Level.SEVERE, null, ex);
        }
        // ======================= start critical section      
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        Gpio.piHiPri(49);     // doc: for root users only ?
        //if (Gpio.piHiPri(49) == -1)
        //  LOG.log(Level.WARNING, "Got error {0}", LinuxFile.errno());
        // returns -1 and LinuxFile.errno() says 1  (no priv.)
        // checking code shows that attempt to change to realtime schedling not working for tomcat8 threads
        
        int count = 0;
        boolean timeErr = false;      // timing error
        boolean startErr = !pinData.isLow();   // DOut should be low here (data ready)");  
        long ntl, nth = System.nanoTime();
        for (int i = 0; i < gain; i++) {
          pinClk.setState(PinState.HIGH);
          ntl = microwait(nth, PULSEMIN);
          timeErr |= (highs[i] = (int)(ntl - nth)/1000) > PULSEMAX;  // check limit
          count *= 2;   //shift (*2)
          if (pinData.isHigh())
            count++;          // +1 
          pinClk.setState(PinState.LOW);
          nth = microwait(ntl, PULSEMIN);
          timeErr |= (lows[i] = (int)(nth - ntl)/1000) > PULSEMAX;  // usuall higher
        }
        /*
	The HX711 output range is min. 0x800000 and max. 0x7FFFFF (the value rolls over).
	In order to convert the range to min. 0x000000 and max. 0xFFFFFF,
	the 24th bit must be changed from 0 to 1 or from 1 to 0.
	*/
        count = count ^ 0x800000;
        
        // another pulse
        pinClk.setState(PinState.HIGH);
        ntl = microwait(nth, PULSEMIN);
        pinClk.setState(PinState.LOW);
        microwait(ntl, PULSEMIN);
        
        // poweroff (keep high for long time) 
        pinClk.setState(PinState.HIGH); // The 25th (or 27th) pulse at PD_SCK input will pull DOUT pin back to high        

        Gpio.piHiPri(10);    // thread finish will do, nevertheless
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        
        // ======================= end critical section
        /* timing exceeded is quite normal on nonrealtime. especially during startup
        * ending with all bits set indicate failure, dont rely on pf, aka avoid to many journal messages
        */
        if (timeErr || count > 0xffffff || (count & 0xff) == 0xff) { 
          // timing exceeded is quite normal on nonrealtime. especially during startup
          if (++failCnt % logred == 0) {
            LOG.log(Level.INFO, "count={0} failCnt={1} startErr={2} timeOvr={3}"
              , new Object[]{Integer.toHexString(count), failCnt, startErr, timeErr});
            if (timeErr) {
              LOG.log(Level.INFO, "Highs:{0}", timing(highs));
              LOG.log(Level.INFO, "Lows: {0}", timing(lows));
            }
          }
        } else {
          double sv = calibrate((double) count);
          if (Double.isNaN(weight)) {
            weight = psv = sv;   // startup, often very faulty value
          } else {
            // plausibiltiy factor for floating mean, see e.g. 1/(5x+1) on https://rechneronline.de/funktionsgraphen/
            double pf = 1.0 / (1.0 + PFSHAPE * Math.abs(sv - psv) / getDelta());
            weight = pf * sv + (1.0 - pf) * weight;
            if (pf < PFMIN || logred == 1) {
              // log when plausibility is very low (bitshift error or huge weight change) or Level.FINE
              LOG.log(Level.INFO, "count={0} sv={1} psv={2} pf={3} weight={4}"
                , new Object[]{Integer.toHexString(count), sv, psv, pf, weight});
              LOG.log(Level.INFO, "Highs:{0}", timing(highs));
              LOG.log(Level.INFO, "Lows: {0}", timing(lows));
            }
            if (pf >= PFMIN) {
              psv = sv;              
              snv = new StampedNV(getName(), Math.round(weight * 100) / 100.0);
              if (failCnt >= logred)   // log when failCnt is high
                LOG.log(Level.INFO, "Errorcount reset after a serie of {0} faults", failCnt);
              failCnt = 0;              
            } else {
              psv = weight;     // dont store without ignoring "far off" values completly
            }
          }
        }
      } else {
        // try again to set into sleep state
        pinClk.setState(PinState.HIGH);
        if (++failCnt % logred == 0)
          LOG.log(Level.INFO, "Unexpected: DOut should be high here (sleep state), failCnt={0}", failCnt);
      }
      return snv;
    }));
    return true;
  }
  
  /**
   * short inline delay
   * @param st timing start
   * @param ns microseconds to expire
   * @return current nano time
   */
  private long microwait (long st, int ns) {
    long till = st + ns * 1000;
    long nt;
    while ((nt = System.nanoTime()) < till){}
    return nt;
  }
  /*
  private int microwait (int ns) {
    return microwait((int)System.nanoTime()/1000, ns);
  }
  */
  
  private String timing(int[]times) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < times.length; i++)
      sb.append(times[i]).append(", ");  // convert to microseconds
    sb.setLength(sb.length() - 2);
    return sb.toString();
  }

  public boolean hasData() {
    if (weightData.isPresent()) {
      return weightData.get().isDone();
    }
    return false;
  }

  @Override
  public void setEnabled(boolean enabled) {
    failCnt = 0;
    super.setEnabled(enabled);
  }
  
  /**
   * @return null if no data yet or faulty or already consumed
   */
  @Override
  public StampedNV getValue() {
    StampedNV data = null;
    if (weightData.isPresent()) {
      CompletableFuture<StampedNV> cf = weightData.get();
      if (cf.isDone()) {
        try {
          data = checked(cf.get());
        } catch (InterruptedException | ExecutionException ex) {
          LOG.log(Level.SEVERE, "", ex);
        }
        weightData = Optional.empty();
      }
    }
    return data;
  }
  
  @Override
  public String getType() {
    return getClass().getName();
  }
}
