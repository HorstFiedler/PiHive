/* $Id: W1Sensor.java,v 1.12 2020/03/21 21:32:39 horst Exp $ */

/**
 * should become base for temperatures sampling
 * TODO: Ensure id fixing, aka T&lt;n&gt;  to Id association  
 * temperature sensor chain has to be connected to 3.3V , GPIO4/Pin 7 (wiringPI default) and GND.
 * See /boot/config.txt  and /boot/overlays/README 
 * dtoverlay=w1-gpio,gpiopin=4
 * Between Data line GPIO4 and 3.3V 4.7k pullup inserted
 * 
 * perform 1-Wire temperature reading (using file IO to system)
 * https://github.com/Pi4J/pi4j/blob/master/pi4j-device/src/main/java/com/pi4j/component/temperature/impl/TmpDS18B20DeviceType.java
 * http://www.reuk.co.uk/wordpress/raspberry-pi/ds18b20-temperature-sensor-with-raspberry-pi/
 * https://github.com/oksbwn/IOT_Raspberry_Pi/blob/master/src/in/weargenius/hardware/WaterTemperatureSensor.java
 * https://www.hackster.io/weargenius/ds18b20-sensor-interfacing-with-raspberry-pi-using-java-e64893?f=1#
pi@pi-zero:~ $ cd /sys/bus/w1/devices/28-800000081063
pi@pi-zero:/sys/bus/w1/devices/28-800000081063 $ cat w1_slave
fb 00 ff ff 7f ff ff ff 03 : crc=03 YES
fb 00 ff ff 7f ff ff ff 03 t=15687
$ lsmod
...
w1_therm                6679  0
w1_gpio                 4566  0
wire                   31600  2 w1_gpio,w1_therm
cn                      5687  1 wire
...
 */

package at.hfff.pi;

import com.pi4j.component.temperature.TemperatureSensor;
import com.pi4j.io.w1.W1Device;

/**
 *
 * @author horst
 */
public class W1Sensor extends Sensor {
  private static final int MINUSECOUNT = 3;
  private W1Device w1d;
  private int useCount = 0;  // to skip first measurement after turned on
  
  public W1Sensor(String[] params) {
    super(params);
  }
  
  /**
   * associate to real device
   * @param w1d 
   */
  public void setDevice(W1Device w1d) {
    this.w1d = w1d;
    setId(w1d.getId());
  }
  
  /**
   * W1 first measurement often fails (huge difference), therefor first measurements after Sensor create are skipped 
   * @return null if no data yet or faulty or  already consumed or not accepted
   */
  @Override
  public StampedNV getValue() {
    double rawValue = ((TemperatureSensor)w1d).getTemperature();
    if (useCount > MINUSECOUNT) {
      StampedNV snv = new StampedNV(getName(), calibrate(rawValue));
      if (isValid(snv))
        return snv;
    } else {
      useCount++;
    }
    return null;
  }

  @Override
  public String getType() {
    return getClass().getName();
  }
}
