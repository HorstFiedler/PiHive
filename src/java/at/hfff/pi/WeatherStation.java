package at.hfff.pi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author horst
 */
public abstract class WeatherStation {
  
  // all sensors including those provided by station
  protected final Map<String, Sensor> sensorMap;
  
  // names of those provided by station and enabled
  protected List<String> channels;
  
  private static final String SEP = ", ";
  
  public abstract void trigger();
  public abstract boolean hasData();
  
  public WeatherStation(Map<String, Sensor> sensorMap) {
    this.sensorMap = sensorMap;
    channels = new ArrayList<>();
  }
  
  protected void setup(NUD[] nudA, Class subClass) {
    // ensure all channels provided by this station are included as sensors
    for (NUD nud : nudA) {
      String sn = nud.params[0];
      Sensor s = sensorMap.get(sn);
      if (s == null) {
        s = new ExtSensor(nud.params, subClass);
        s.setId(Integer.toString(nud.id));
        s.setEnabled(false);
        sensorMap.put(sn, s);
      } else {
        if (s.isEnabled())
          channels.add(sn);
      }
      
    }
  }
  
  // holding description of channels provided
  protected class NUD {
    String[] params;
    int id;    // used as locator  
    NUD(String[] n, int i) {
      params = n;
      id = i;
    }
  }
}


