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
 * Trying to overcome failing measurements by fault dedection and nice floating
 * mean
 *
 * @author horst
 */
public class HXSensor extends Sensor {

  private Optional<CompletableFuture<StampedNV>> weightData = Optional.empty();

  private final GpioPinDigitalOutput pinClk;
  private final GpioPinDigitalInput pinData;

  // for plausibility check 
  private static final double PFMIN = 0.1; // ignore data raw data when plausibility is less than PFMIN
  private static final double PFSHAPE = 16.0;   // > 0 , higher value reduces noise but decreases reaction time
  private double weight = Double.NaN;  // weight (mean)
  private double psv = Double.NaN; // preceeding scaled measured value

  // channel A, gain factor 128 : 24
  // channel A, gain factor 64 : 26
  // channel B, gain factor 32 : 25
  private final int gain = 24;   // ticks for high precission  (HX711 default)

  // retry limit ( ~10 minutes)
  private static final int ERRMAX = 600;

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
   * WARNING: count=87ffff sv=25.132 pf=0.066 weight=23.799
   * 42999, 28000, 27000, 25999, 25999, 83998, 26999, 25999, 27000, 25000, 24999, 25999, 27000, 24999, 24999, 27000, 28000, 24999, 24999, 26000, 25999, 25999, 25000, 28000
   */
  private static final long PULSEMAX = 80000;   // 60 µsec but we allow 80 here, measured for data pin
  // since bullseye,tomcat9 with zulu11 java minwidth of pulse required!?
  private static final long PULSEMIN = 30000;   // 30 µsec, used for clk pin
  
  private volatile int failCnt = 0;
  private final long[] highs = new long[gain];  // record high pulse timing for debug 
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
   * prepare optional future see
   * ~/Dokumente/elektronik/raspberry/packages/HX711-Pi4j-master/HX711.java
   * CAVEAT: With user level java there is no chance to ensure max 60
   * microseconds shift pulses
   *
   * @return false if no measurement is started (e.g. due to ERRMAX exceeded)
   */
  public boolean trigger() {
    if (failCnt >= ERRMAX) {
      LOG.log(Level.WARNING, "Errorlimit exceeded");
      return false;
    }
    weightData = Optional.of(CompletableFuture.supplyAsync(() -> {
      // return value, nonnull if ok
      StampedNV snv = null;

      // reduced logging when not FINE
      int logred = LOG.isLoggable(Level.FINE) ? 1 : ERRLOG;
      
      // assuming sleep state, aka HIGH 
      if (pinData.isHigh()) {
        pinClk.setState(PinState.LOW);    // return to normal mode (wakeup)

      
        Arrays.fill(highs, 0);   // for pulse timing check   
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
        for (int i = 0; i < gain; i++) {
          long nt = System.nanoTime();
          pinClk.setState(PinState.HIGH);
          count = count << 1;   //shift (*2)
          nanowait(nt, PULSEMIN);
          pinClk.setState(PinState.LOW);
          timeErr = timeErr || ((highs[i] = System.nanoTime() - nt) > PULSEMAX);  // overall time shall no exceed PULSEMAX
          nanowait(PULSEMIN); 
          if (pinData.isHigh())
            count++;          // +1 
        }
        count = count ^ 0x800000;     // 2 complement
        
        // another pulse. the 25th (or 27th) pulse at PD_SCK input will pull DOUT pin back to high
        pinClk.setState(PinState.HIGH);
        nanowait(PULSEMIN);
        pinClk.setState(PinState.LOW);
        nanowait(PULSEMIN);
        
        // poweroff (keep high for long time) 
        pinClk.setState(PinState.HIGH); // The 25th (or 27th) pulse at PD_SCK input will pull DOUT pin back to high        

        Gpio.piHiPri(10);    // thread finish will do, nevertheless
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        
        // ======================= end critical section
        if (timeErr || count >= 0x7fffff) {     // ffffff, 7fffff are definitly wrong
          // timing exceeded is quite normal on nonrealtime. especially during startup
          if (++failCnt % logred == 0) 
            LOG.log(Level.INFO, "count={0} failCnt={1} startErr={2} timeOvr={3}\n{4}"
              , new Object[]{Integer.toHexString(count), failCnt, startErr, timeErr, timing(highs)});
        } else {
          double sv = calibrate((double) count);
          if (Double.isNaN(weight)) {
            weight = psv = sv;   // startup
          } else {
            // plausibiltiy factor for floating mean, see e.g. 1/(5x+1) on https://rechneronline.de/funktionsgraphen/
            double pf = 1.0 / (1.0 + PFSHAPE * Math.abs(sv - psv)* getDelta());
            weight = pf * sv + (1.0 - pf) * weight;
            if (pf < PFMIN || logred == 1) { // log when plausibility is very low (bitshift error or huge weight change)
              LOG.log(Level.INFO, "count={0} sv={1} psv={2} pf={3} weight={4}"
                , new Object[]{Integer.toHexString(count), sv, psv, pf, weight});
              LOG.log(Level.FINE, "{0}", timing(highs));
            }
            if (pf > PFMIN) {   // completly ignore otherwise
              psv = sv;              
              snv = new StampedNV(getName(), Math.round(weight * 100) / 100.0);
            }
          }
          if (failCnt >= logred)
            LOG.log(Level.INFO, "Errorcount reset after a serie of {0} faults", failCnt);
          failCnt = 0;
        }
      } else {
        // try again to set into sleep state
        pinClk.setState(PinState.HIGH);
        if (failCnt++ % logred == 0)
          LOG.log(Level.INFO, "Unexpected: DOut should be high here (sleep state), failCnt={0}", failCnt);
      }
      return snv;
    }));
    return true;
  }
  
  /**
   * short inline delay
   * @param st timing start
   * @param ns nanoseconds to expire
   */
  private void nanowait (long st, long ns) {
    long till = st + ns;
    while (System.nanoTime() < till){}
  }
  private void nanowait (long ns) {
    nanowait(System.nanoTime(), ns);
  }
  
  /**
   * record timings for debugging
   * @param highs
   * @return 
   */
  private String timing(long[] highs) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < highs.length; i++)
      sb.append(highs[i]).append(", ");
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
