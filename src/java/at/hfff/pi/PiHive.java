package at.hfff.pi;

import at.hfff.pi.ws.PiEndpoint;
import com.pi4j.component.temperature.impl.TmpDS18B20DeviceType;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigital;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.w1.W1Device;
import com.pi4j.io.w1.W1Master;
import com.pi4j.wiringpi.GpioUtil;
import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Tomcat deployable adaption 
 */
public class PiHive implements Runnable {

  private static PiHive instance;
  private static String netName; 
  private static Thread mainLoop;
  private static GpioController gpio;

  // create timer queue (filled by input change listeners, processed within mainloop)
  private static final DelayQueue<DelayableOrder> TQ = new DelayQueue<>();

  // heartbeat loop in milliseconds (to handle I2C channels and other cleanup)
  private static final int HB_DELAY = 1000;
  
  // weatherdata fetch (OWM seens to update 2x/h, ZAMG has one hour data but duplicates are ignored anyway
  private static final int WS_DELAY = 30 * 60 * 1000;
  
  // enable use of tail -f /var/log/tomcat8/catalina.out, used by PiEndpoint too
  public static final Logger LOG = Logger.getLogger(PiHive.class.getName());

  private final static String DATALOG="data.log";
  private final static String CVIEW="cview.ser";
  private final static String SENSORS="sensors.cfg";
  private final static String PUBLISH="publish.ser";
  private final static String ARCHIVE="archive.ser";
  
  private final History history = new History();
  
  // to pass starttime when client doesnt set it
  int tlstart = -3600000 * 24;    // default 1 day back
  int tlend = 0;   // now
  

  // partially (superclass only) persistent
  // generics problem, avoiding generic by using SensorMap.class in dynamic constructor doesnt help to avoid "unchecked" !!!!
  //class SensorMap extends HashMap<String, Sensor> {}
  //private final SensorMap sensorMap = new SensorMap();
  private final Map<String, Sensor> sensorMap = new HashMap<>();
  
  // actorList not yet (S4FH has PwrCtl actor)
  
  // publishing graph and log (optional)
  private Publisher publisher;
  private Archiver archiver;
  
  // weatherstation data, either OpenWeaterMap or ZAMG
  private WeatherStation wsd;
  
  // persistent data
  private File persistDir;

  protected static final SimpleDateFormat DDF = new SimpleDateFormat("yyMMdd-HHmm");
  
  // singleton pattern
  private PiHive() {
  }

  public static PiHive getInstance() {
    if (instance == null)
      instance = new PiHive();
    return instance;
  }
  
  public static void startDaemon() {
    mainLoop = new Thread(instance);
    mainLoop.setName("PiMainLoop");
    mainLoop.setDaemon(true);
    mainLoop.start();
  }
  
  /**
   * called by contextListener! (to have name available)
   * @param name is servlet name to locale persistence directory
   * @return false on severe error
   */
  public boolean configure(String name) {
    // for early testing start with higher loglevel (see web/WEB-INF/classes/logging.properties
    //LOG.setLevel(Level.FINE);
    //LOG.log(Level.INFO, "Properties: {0}", System.getProperties());
    try {
      // may throw UnknownHostException when nameservice not yet ok (e.g. after hostname change)
      netName = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ex) {
      LOG.log(Level.WARNING, "Check nameservice for validity!!!, fix eg. by wlan router reboot");
      netName = ex.getMessage();  
    }
    // where additional application data are placed
    // TODO: into logs with names pihive-data.log, phihive-sensors.cfg, ...
    //  to allow security strict within tomcat9.service
    persistDir = new File(System.getProperty("catalina.base") + "/persist/" + name.toLowerCase());
    if (!persistDir.canRead()) 
      if (!persistDir.mkdir())
        LOG.log(Level.SEVERE, "Unable to create servlets persistence files at {0}", persistDir.getAbsolutePath());
    
    // setup data logger
    File logData = new File(persistDir, DATALOG);

    // get old data
    List<StampedNV> nvL = new ArrayList<>();
    try (LineNumberReader fr = new LineNumberReader(new FileReader(logData))) {
      String line;
      while ((line = fr.readLine()) != null) {
        String[] columns = line.split("\\t");
        if (columns.length == 3) {
          try {
            nvL.add(new StampedNV(StampedNV.SDF.parse(columns[0]).getTime(), columns[1], Double.parseDouble(columns[2])));
          } catch (ParseException | NumberFormatException ex) {
            LOG.log(Level.WARNING, "Load error {0} at {1}", new Object[]{ex.getMessage(), line});
          }
        } else {
          LOG.log(Level.INFO, "Line {0} ignored", line);
        }
      }
      LOG.log(Level.INFO, "#Log entries read: {0}", nvL.size());
      
      history.setLogWriter(new PrintWriter(new FileWriter(logData), true));
      history.addAll(nvL.stream().sorted((StampedNV o1, StampedNV o2) -> o1.compareTo(o2)).distinct().collect(Collectors.toList()));
      LOG.log(Level.INFO, "#Log entries restored:{0}", history.size());
    } catch (IOException ioe) {
      LOG.log(Level.SEVERE, "Log {0} create failed: {1}", new Object[]{logData, ioe.getMessage()});
      return false;
    }
    
    // gpio setup, required to create gpio based sensors (W1, HX)
    // w1-gpio on GPIO_07 (BCM #4), pin  is defined within config.cfg
    // hx711 CLK and DAT are defined below (GPIO_22 und GPIO_21)
    GpioUtil.enableNonPrivilegedAccess();   // -> not necessary, pi and tomcat8 are in gpio group
    gpio = GpioFactory.getInstance();
    
    // load sensors (if any)
    Class sClass = null;
    File sensorData = new File(persistDir, SENSORS);
    if (sensorData.canRead()) {
      // read data and merge into sensorMap, simple sensors are created
      try (LineNumberReader fr = new LineNumberReader(new FileReader(sensorData))) {
        String line;
        while ((line = fr.readLine()) != null) {
          if (!line.startsWith("#")) {
            LOG.log(Level.FINE,"{0}", line); 
            String[] columns = line.split(", ");
            if (columns.length > 5) {
              String cname = columns[0]; 
              String ctyp = columns[3];
              if (ctyp.endsWith("W1Sensor")) {
                sensorMap.put(cname, new W1Sensor(columns));
              } else if (ctyp.endsWith("HXSensor")) {
                // HX711 weight sensor
                // TODO: make used gpio pins configurable
                // using clk  PinState.HIGH to make reset doesnt help against error on first measurement
                sensorMap.put(cname, new HXSensor(columns
                  , gpio.provisionDigitalOutputPin(RaspiPin.GPIO_22, "HX_CLK", PinState.LOW)
                  , gpio.provisionDigitalInputPin(RaspiPin.GPIO_21, "HX_DAT", PinPullResistance.OFF)
                ));
              } else {
                // multichanel devices are created later
                try {
                  sClass = Class.forName(ctyp);   // not a HashSet yet, aka only! one 
                  sensorMap.put(cname, new ExtSensor(columns, sClass));
                } catch (ClassNotFoundException ex) {
                  LOG.log(Level.WARNING, "", ex);
                }
              }
            } else {
              LOG.log(Level.WARNING, "Invalid sensor config line: {0}", line);
            }  
          } // else #comment (not yet preserved)
        }
      } catch (IOException ioe) {
        LOG.log(Level.WARNING, "", ioe);
      }
    }
    
    // to provide  snvLatest for sensors
    HashMap<String,StampedNV> latest = new HashMap<>();
    history.forEach((snv) -> {
      latest.put(snv.source, snv);
    });
    sensorMap.values().forEach((s) -> {
      StampedNV snv = latest.get(s.getName());
      if (snv != null)
        s.setLast(snv);      
      LOG.log(Level.INFO, s.toString());
    }); 
    
    // provide 1Wire sensors identified by id, apply setting to identified one
    W1Master master = new W1Master();
    List<W1Device> w1Devices = master.getDevices(TmpDS18B20DeviceType.FAMILY_CODE);
    for (int i = 0; i < w1Devices.size(); i++) {
      // check if already configured
      boolean old = true;
      W1Sensor w1s = null;
      W1Device w1d = w1Devices.get(i);
      String id = w1d.getId();
      for (Sensor s : sensorMap.values()) {
        if (s instanceof W1Sensor) {
          if (s.getId().equals(id)) {
            w1s = (W1Sensor) s;
            w1s.setDevice(w1d);
            break;
          }
        }
      }
      if (w1s == null) {
        old = false;
        // create a new sensor using a primitive serial uniqueness creator
        String sname = "T";
        w1s = new W1Sensor(new String[]{sname, "Temperature", "°C", "", id, Boolean.toString(true)});
        int uido = 0;
        int uid = 1;
        while (uido != uid) {
          uido = uid;
          if (sensorMap.containsKey("T" + uid))
            uid++;
        }
        sname = sname + uid;
        w1s.setName(sname);
        w1s.setDevice(w1d);
        w1s.setCeck(Sensor.FType.MINDIFF);
        w1s.setDelta(0.5);  // overwrite default
        sensorMap.put(sname, w1s);
      }
      LOG.log(Level.INFO, "W1Sensor({1}): {0}", new Object[]{w1s, old});
    }
    
    // optional weatherstation
    if (sClass != null) {
      // add multichannel station(s) 
      // !!! currently only one !!!
      try {
        if (sClass.getSuperclass().getName().endsWith("WeatherStation")) {
          // additional channels may be added if not yet within sensors.cfg
          //warning: [unchecked] unchecked call to getConstructor(Class<?>...) as a member of the raw type Class
          //wsd = (WeatherStation)sClass.getConstructor(SensorMap.class).newInstance(sensorMap);
          wsd = (WeatherStation)sClass.getConstructor(Map.class).newInstance(sensorMap);
          // schedule first weatherdata fetch
          TQ.add(new DelayableOrder(DelayableOrder.WSTRIGGER)); 
        } else {
          LOG.log(Level.WARNING, "Invalid class {0}", sClass.getSuperclass());
        }
      } catch (InstantiationException
        | NoSuchMethodException
        | SecurityException
        | InvocationTargetException
        | IllegalAccessException cex) {
        LOG.log(Level.WARNING, "", cex);
      }
    }
    
    // load/create publish parameters and schedule first stores
    try (FileInputStream fis = new FileInputStream(new File(persistDir, PUBLISH)); ObjectInputStream ois = new ObjectInputStream(fis)) {
      publisher = (Publisher)ois.readObject();
    } catch (ClassNotFoundException | IOException ex) {
      publisher = new Publisher();    // load defaults (empty urlpattern)
    }
    LOG.log(Level.INFO, "Publisher destination: {0}", publisher.urlpattern);
    TQ.add(new DelayableOrder(DelayableOrder.PUBLISH, 60000));  // first time after 1 minute 
          
    // load/create archiver parameters
    try (FileInputStream fis = new FileInputStream(new File(persistDir, ARCHIVE)); ObjectInputStream ois = new ObjectInputStream(fis)) {
      archiver = (Archiver)ois.readObject();
    } catch (ClassNotFoundException | IOException ex) {
      archiver = new Archiver();
    }
    LOG.log(Level.INFO, "Archive destination: {0}", archiver.urlpattern);
    TQ.add(new DelayableOrder(DelayableOrder.ARCHIVE, 3600000));  // first time after 1 h
    return true;
  }
 
  /**
   * main loop runs on preconfigured PI and usually only stops on servlet stop
   * the Pi instance is destroyed and has to be reconfigured using getInstance()
   */
  @Override
  public void run() {
    LOG.log(Level.INFO, "Starting mainloop");
    try {
      while (true) {
        // entries created by change listeners
        DelayableOrder te = TQ.poll(HB_DELAY, TimeUnit.MILLISECONDS);
        // skip canceled orders
        if (te != null && !te.isCanceled()) {
          LOG.log(Level.FINE, "Processing {0}", te);
          // analyse underlying switch events
          switch (te.getType()) {
            case DelayableOrder.WSTRIGGER:   // just trigger weatherstation, the result is fetched in loop 
              wsd.trigger();
              TQ.add(new DelayableOrder(DelayableOrder.WSTRIGGER, WS_DELAY));  // reschedule
              break;
              
            case DelayableOrder.PUBLISH:   // write graphics to homepage TODO: extra module to be executed async (future task)
              TQ.add(new DelayableOrder(DelayableOrder.PUBLISH, publisher.publish(this, netName)));
              break;
              
            case DelayableOrder.ARCHIVE:   // archive log data TODO: extra module to be executed async (future task)
              TQ.add(new DelayableOrder(DelayableOrder.ARCHIVE, archiver.archive(history, netName)));
              break;

            default:
              LOG.log(Level.WARNING, "Unexpected event {0}, te");
          }
          te.cancel(); 
        }
        TQ.removeIf(t -> t.isCanceled());

        // weather station sensors might have new values
        boolean checkExt = wsd != null && wsd.hasData(); 
        
        // read temperature values every second
        //TODO: channels like OWMData shall become sensors too and for timing a countdown latch 
        // then TimerQueue above might become obsolete and replaced by simple sleep
        sensorMap.values().forEach((sensor) -> {   
          // read preceeding measurement
          if (sensor.isEnabled()) {
            if (!(sensor instanceof ExtSensor) || checkExt) {
              StampedNV snv = sensor.getValue();  // includes sensorspecific check if any
              if (snv != null)  
                history.addLast(snv);
            }
            // resultindepend start new measurement
            if (sensor instanceof HXSensor) {
              if (!((HXSensor) sensor).trigger()) {
                sensor.setEnabled(false);
                LOG.log(Level.WARNING, "Sensor {0} malfunction, disabled", sensor.getName());
              }
            }
          }
        });    
      } // while true
    } catch (InterruptedException | RuntimeException ext) {
      LOG.log(Level.SEVERE, "Mainloop interrupted", ext);
    }
    TQ.clear();
    history.close();
    
    // save sensordata (write only abstract superclass data)
    File sensorData = new File(persistDir, SENSORS);
    try (PrintWriter fw = new PrintWriter(sensorData)) {
      sensorMap.values().forEach((s) -> {
        fw.println(s.toString());
      });
    } catch (IOException ex) {
      LOG.log(Level.SEVERE, null, ex);
    }
  
    // save archive data and parameters
    if (archiver != null) {
      archiver.archive(history, netName);   // final archive action
      try (FileOutputStream fos = new FileOutputStream(new File(persistDir, ARCHIVE)); ObjectOutputStream oos = new ObjectOutputStream(fos)) {
        oos.writeObject(archiver);
      } catch (IOException ex) {
        LOG.log(Level.SEVERE, null, ex);
      }
    }
    
    // save publish parameters
    if (publisher != null)
      try (FileOutputStream fos = new FileOutputStream(new File(persistDir, PUBLISH)); ObjectOutputStream oos = new ObjectOutputStream(fos)) {
        oos.writeObject(publisher);
      } catch (IOException ex) {
        LOG.log(Level.SEVERE, null, ex);
      }
    
    // on start a new instance shall be created
    instance = null;
  }

  /**
   * allows to force exit of mainloop
   */
  public void terminate() {
    mainLoop.interrupt();
    try {
      while (instance != null) {
        Thread.sleep(100);
      }
    } catch (InterruptedException ex) {
    }
  }

  public boolean isAlive() {
    return mainLoop.isAlive();
  }

  /**
   * commands for action not suitable for set/getValue pattern
   * @param cmd command
   * @param args stringified further arguments, empty on most simple (void) commands
   * @return requested info, command output, ... depending on cmdA[1]
   */  
  public String sysCommand(String cmd, String args) {
    LOG.log(Level.FINE, "syscmd {0} {1}", new Object[] {cmd, args});
    String reply = ""; // as length == 0 : No reply default
    boolean snv = false;  // true when reply shall be stamped (to be used as setter for client variables)
    switch (cmd) {
      case "reset":
        history.clear();
        // by adding to history a reply is generated anyway
        history.addLast(new StampedNV("reset", 0));
        break;
      case "history":
        reply = history.toString();
        break;
      case "shutdown":  //NOT YET privilege missing, see raspberry/faults/policykit
        try {
          // ensure shutdown executable by tomcat 
          Process proc = Runtime.getRuntime().exec("/sbin/shutdown -h -t 10)");
          reply = cmd + " exit value: " + proc.exitValue();
        } catch (IOException ex) {
          reply = ex.getMessage();
          LOG.log(Level.SEVERE, null, ex);
        }
        break;
      case "loglevel":  // get or set log level
        Level level;
        if (!args.isEmpty()) {
          level = Level.parse(args);
          LOG.setLevel(level);
          LOG.log(Level.INFO, "Logging changed to {0}", level);
        } else {
          level = LOG.getLevel();
        }
        if (level == null) level = Level.INFO;
        reply = level.getName();
        snv = true;
        break;
      case "archive": // get or set
        if (!args.isEmpty()) {
          //timeline delay urlpattern
          archiver.parametrize(args);
          archiver.archive(history, netName);
        } else {
          reply = archiver.params();
          snv = true;
        }
        break;
      case "publish":
        if (!args.isEmpty()) {
          publisher.parametrize(args); // ATTENTION time args in hours!
          publisher.publish(this, netName);
        } else {
          reply = publisher.params();
          snv = true;
        }
        break;
     case "cview":  // "Upload", aka transfering client local storage item "cview" to server (create cview.ser persistent file)
        try (FileOutputStream fos = new FileOutputStream(new File(persistDir, CVIEW)); ObjectOutputStream oos = new ObjectOutputStream(fos)) {
          String[] argA = args.split(" ");
          // publisher, vectorgraphics, ... , most will overrule width, heigth and timeline 
          for (int i = 0; i < 3; i++)
            oos.writeInt(Integer.parseInt(argA[i])); // width, heigth, timeline hrs
          String name = "";
          Color color = null;
          int axis = -1;   
          for (int i = 3; i < argA.length; i++) {
            try {
              String word = argA[i];
              switch (i % 4) {
                case 3: name = word; break;
                case 0: color = new Color(Integer.parseUnsignedInt(word.substring(1), 16)); break;
                case 1: axis = Integer.parseUnsignedInt(word); break;
                case 2: if (!name.isEmpty())
                  oos.writeObject(new CView(name, color, axis, Boolean.valueOf(word)));
                break;
              }
            } catch (NumberFormatException ex) {
              LOG.log(Level.SEVERE, "view formating error ", ex);
            }    
          }
        } catch (IOException ex) {
          LOG.log(Level.INFO, "", ex);
        }
        break;

      case "graphics":  
        String[] argA = args.split(" ");
        if (argA.length < 2)
          reply = "Usage: graphics <width> <height> [hours  [name color axis isbinary] [...] ... ]";
        else {
          ArrayList<CView> cViewL;
          if (argA.length > 3) {   // when inline from  current page graphics dialog page
            cViewL = new ArrayList<>();
            String name = "";
            Color color = null;
            int axis = -1;
            for (int i = 3; i < argA.length; i++) {
              try {
                String word = argA[i];
                switch (i % 4) {
                  case 3: name = word; break;
                  case 0: color = new Color(Integer.parseUnsignedInt(word.substring(1), 16)); break;
                  case 1: axis = Integer.parseUnsignedInt(word); break;
                  case 2: if (!name.isEmpty())
                    cViewL.add(new CView(name, color, axis, Boolean.valueOf(word)));
                  break;
                }
              } catch (NumberFormatException ex) {
                LOG.log(Level.SEVERE, "view formating error ", ex);
              }    
            }
          } else {
            // load persistent, e.g. when psvg.html pages is loaded, includes tlstart setting
            cViewL = loadCView();
          }
          if (argA.length > 2)  // optional, otherwise currently set one (preceeding request or peristent by loadCView)
            tlstart = -3600000 * Integer.parseInt(argA[2]);  // startpoint relative to now (milliseconds)
          long ct = System.currentTimeMillis();
          reply = getSVG(Integer.parseInt(argA[0]), Integer.parseInt(argA[1]), ct + tlstart, ct + tlend, cViewL);
        }
        break;
      case "version":
        reply = "$Id: PiHive.java,v 1.22 2021/09/02 10:09:05 horst Exp $";
        break;
      case "hostname":  // tell client to which host it is connected
        reply = netName;
        snv = true;
        break;
      default:
        reply = cmd + ' ' + args + " ignored";
    }    
    if (snv) 
      reply = new StampedNV(cmd, reply).toString();  // sending value e.g. to set element loglevel at clients
    return reply;
  }

  private ArrayList<CView> loadCView() {
    ArrayList<CView> cViewL = new ArrayList<>();
    File cvf = new File(persistDir, CVIEW);
    try (FileInputStream fis = new FileInputStream(cvf); ObjectInputStream ois = new ObjectInputStream(fis)) {
      int w = ois.readInt();   // unused, allways clientprovided (due to FHChart - compatibility)
      int h = ois.readInt();   // unused, clientprovided
      tlstart = -3600000 * ois.readInt();  // new default when not provided by client
      while (fis.available() > 0) {
        CView cv = (CView)ois.readObject();
        if (!cv.name.isEmpty())
          cViewL.add(cv);
      }
    } catch (IOException | ClassNotFoundException ex) {
      LOG.log(Level.SEVERE, "View load failed after " + cViewL.size() + " entries: ", ex);
    }
    return cViewL;
  }
  
  /**
   * Without arguments the start time is constant and view is taken from persistent
   * (used when posting svg file using Publisher, aka w,h,hours are within Publisher.ser)
   * @param w
   * @param h
   * @param hours
   * @return 
   */
  protected String getSVG(int w, int h, int hours) { 
    ArrayList<CView> cViewL = loadCView();
    if (cViewL.isEmpty())
      return "Missing chart settings \"cview.ser\"";
    long ct = System.currentTimeMillis();
    //TODO: Allow tlstart to be set in export dialog
    return getSVG(w, h, ct - 3600000 * hours, ct, cViewL);
  }
  
  /**
   * get SVG of size wxh over values newer then stime
   * @param w
   * @param h
   * @param sTime in milliseconds 
   * @param cViewL channels to show, if empty an attemt is made to read it from peristent store
   * @return SVG data
   * ATTENTION: As long as no cview selection possible by user
   * some channels are removed when sTime &lt; (now - 24h)
   * TODO: Allow channel drop by client as long as there is no complete client side property editor
   */
  private String getSVG(int w, int h, long sTime, long eTime, ArrayList<CView>cViewL) { 
    LOG.log(Level.FINE, "w={0} h={1} stime={2} cViewL(received)={3}", new Object[] {w, h, sTime, cViewL});
    
    //                      title                                  subtitle
    Chart chart = new Chart(netName + " @ " + DDF.format(new Date()), "Aktuell: ", cViewL);
    synchronized(history) {
      history.forEach((hist) -> {
        if ( hist.pTime >= sTime && hist.pTime <= eTime)
        chart.appendData(hist.pTime, hist.source, hist.value);
      });
    }
    //TODO: check PiEndPoint session.setMaxTextMessageBufferSize() for not exceeding
    return chart.getSVG(w, h);
  }
  
  /**
   * get digital in- or output value
   *
   * @param name shall be the name of a provisioned digital pin
   * @return 0 if pinstate is low!!!! (open collector putput or pulldown inputs)
   */
  public String getPinValue(String name) throws IllegalArgumentException {
    GpioPinDigital pin = (GpioPinDigitalOutput) gpio.getProvisionedPin(name);
    if (pin == null) {
      throw new IllegalArgumentException("invalid name " + name);
    }
    return new StampedNV(name, pin.getState().getValue()).toString();
  }

  /**
   * set digital output
   *
   * @param name shall be the name of a provisioned output pin
   * @param value (0 for low, 1 for high)
   * @return names-value (changed or unchanged) or error
   */
  public String setPinValue(String name, int value) {
    GpioPinDigitalOutput pin = (GpioPinDigitalOutput) gpio.getProvisionedPin(name);
    if (pin == null) {
      LOG.log(Level.WARNING, "setPinValue({0},{1}) unknown pin", new Object[]{name, value});
      return "Invalid pin";
    }
    if (pin.getState().getValue() == value) {
      LOG.log(Level.WARNING, "setPinValue({0},{1}) ignored", new Object[]{name, value});
    } else {
      // other (e.g. SSR's)
      pin.setState(value == 0 ? PinState.LOW : PinState.HIGH);
      history.add(new StampedNV(name, value));
    }
    // log even if unchanged to reply the correct value
    return new StampedNV(name, pin.getState().getValue()).toString();
  }

  /**
   * get value of a variable
   *
   * @param name is sensorname with some special cases for taring  
   * @return String timestamp + name + value
   * @throws IllegalArgumentException when name not available or accessibility
   * privat
   */
  public String getValue(String name) throws IllegalArgumentException {
    StampedNV nv = null;
    Sensor s;
    switch (name) {
      case "WTSensor": // just state (enabled true/false)
      case "WTTare":   // current weight as tare start value
        s = sensorMap.get("WT");
        if (s != null) {
          if (name.equals("WTSensor"))
            nv = new StampedNV(name, s.isEnabled() ? 0 : 1);
          else {
            nv = s.getLast();
            if (nv != null)
              nv = new StampedNV(name, nv.value);
          }            
        }
        break;

      default:
        s = sensorMap.get(name);
        if (s != null)
          nv = s.getLast();
        break;
        
        /* addressing local field (with single redirection) removed
        * field needs to be at least 'protected' to be accessible from here
        * qualified name shall be (classname.fieldname)
        try {
          int lsep = name.lastIndexOf('.');
          String lName = name;
          String rName = null;
          if (lsep > 0) {
            lName = name.substring(0, lsep);
            rName = name.substring(lsep + 1);
          }
          if (rName == null) {
            value = PiHive.class.getDeclaredField(lName).get(this);
          } else {
            // pure experimental:
            Object obj = PiHive.class.getDeclaredField(lName).get(this);
            Class cl = obj.getClass();
            if (cl.getName().endsWith("Sensor"))
              cl = cl.getSuperclass();
            LOG.log(Level.INFO, "name={0} obj={1} class={2}", new Object[]{name, obj, cl});
            value = cl.getDeclaredField(rName).get(obj);
          }
        } catch (NoSuchFieldException | SecurityException | IllegalAccessException | ArrayIndexOutOfBoundsException ex) {
          LOG.log(Level.WARNING, name, ex);
        }
        */
    }
    if (nv == null) {
      if (s == null)
        LOG.log(Level.WARNING, "No sensor for {0}", name);
      else
        LOG.log(Level.WARNING, "No value for {0}", name);
      nv = new StampedNV(name, Double.NaN);
    }
    LOG.log(Level.FINE,"getValue({0}) -> {1}", new Object[]{name, nv});
    return nv.toString();
  }

  /**
   * set value of a variable
   *
   * @param name name of sensor (or special name)
   * @param arg is string which has to be decoded/parset to create setter argument
   * Currently sensor.name true|false can be use to enable/disable sensors 
   * @throws IllegalArgumentException when name not available or accessibility
   * privat
   */
  public void setValue(String name, String arg) throws IllegalArgumentException {
    Sensor sensor;
    switch (name) {
      case "WTSensor":
        sensor = sensorMap.get("WT");
        if (sensor != null) {
          boolean onoff = Integer.parseInt(arg) == 0;
          if (sensor.isEnabled() == onoff) {
            LOG.log(Level.WARNING, "Sensor {0} state unchanged", sensor.getName());
          } else {
            sensor.setEnabled(onoff);
            history.add(new StampedNV(name, onoff ? 0 : 1)); // no text yet allowed as value
            LOG.log(Level.INFO, "Sensor {0} state changed: {1}", new Object[]{sensor.getName(), sensor.isEnabled()});
          }
        } else {
          LOG.log(Level.WARNING, "No \"WT\" sensor");
        }
        break;
      case "WTTare":
        sensor = sensorMap.get("WT");
        if (sensor != null) {
          double setWeight = Double.parseDouble(arg);
          double curWeight = (double) sensor.getLast().value;
          String before = sensor.toString();
          String calTyp = sensor.setTare(setWeight, curWeight) ? "offset" : "slope"; 
          String after = sensor.toString();
          LOG.log(Level.INFO, "WTTare {0} ==> {1}\n  typ={2}\n  before={3}\n  after={4}", 
            new Object[]{curWeight, setWeight, calTyp, before, after});
        } else {
          LOG.log(Level.WARNING, "No \"WT\" sensor");
        }
        break;
      case "Z": /* Zucker */
      case "V": /* Varroa */
        history.add(new StampedNV(name, arg));
        break;
      default:
        /* Experimental, qualified names to access foreign classes never used
         * name is Pi (this) local fieldname (direct)  or local fieldname[n].fieldname in object referenced by fieldname[n]
         * to access objects which are members of this (PiHive) class, e.g. 
      try {
        int lsep = name.lastIndexOf('.');
        String lName = name;
        String rName = null;
        if (lsep > 0) {
          lName = name.substring(0, lsep);
          rName = name.substring(lsep + 1);
        }
        if (rName == null) {
          // Pi local access
          Field lf = PiHive.class.getDeclaredField(lName);
          Object obj = lf.get(this);
          if (!arg.equals(obj.toString())) {
            //WARNING: Only for integers in Pi class !!!!
            lf.setInt(this, Integer.parseInt(arg));
            history.add(new StampedNV(name, arg));  // no text yet allowed as value
          }
        } else {
          //WARNING: no array or arraylist access using obj (obj cannot be array yet)
          // and therefor sensorlist not yet accessible -> using hard coded access instead of
          // Field rf = obj.getClass().getDeclaredField(rName);
          // Object rObj = rf.get(obj);
          //TODO: complete experiments
        }
      } catch (NoSuchFieldException | SecurityException | IllegalAccessException | IllegalArgumentException ex) {
        LOG.log(Level.WARNING, "Failed to set value", ex);
        throw new IllegalArgumentException(name + ": " + arg + " invalid");
      }
        */
    }
  }

  // keep references to websocket SendHandler to allow broadcasting of change notifications
  public void addClient(String id, PiEndpoint.OutputFeeder receiver) {
    history.addClient(id, receiver);
  }

  public void removeClient(String id) {
    history.removeClient(id);
  }
 
  /**
   * For Debugging: the start method may be called without websocket environment
   * Nevertheless: requires Raspberry, otherwise UnsatisfiedLinkError
   *
   * @param args the command line arguments : unused
   */
  public static void main(String args[]) {
    PiHive pi;
    LOG.log(Level.INFO, "Test Program Startup");
    try {
      // configure hardware and start mainloop
      pi = PiHive.getInstance();

      // stop after 10 minutes
      int seconds = 0;
      while (pi.isAlive()) {
        Thread.sleep(1000);
        if (seconds++ > 600) {
          pi.terminate();
        }
      }
      LOG.log(Level.INFO, "processed@\tsource\t\ttimeevent\tvalue\n{0}\n{1}", pi.sysCommand("history", ""));
    } catch (InterruptedException ex) {
      LOG.log(Level.INFO, "Exception: {0}", ex);
    }
  }
}
