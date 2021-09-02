/* $Id: OWMStation.java,v 1.2 2019/11/13 08:13:26 horst Exp $ */
package at.hfff.pi;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.openweathermap.api.DataWeatherClient;
import org.openweathermap.api.UrlConnectionDataWeatherClient;
import org.openweathermap.api.model.MainParameters;
import org.openweathermap.api.model.currentweather.CurrentWeather;
import org.openweathermap.api.query.Language;
import org.openweathermap.api.query.QueryBuilderPicker;
import org.openweathermap.api.query.ResponseFormat;
//import org.openweathermap.api.trigger.Type;
import org.openweathermap.api.query.UnitFormat;
import org.openweathermap.api.query.currentweather.CurrentWeatherOneLocationQuery;

/**
 * testing open weather map, see ~/netbeans/OWMTest, Client shall prepare
 * unconditionally on sampling start, and on data received + 1/2 h
 *
 * @author horst
 */
public class OWMStation extends  WeatherStation {
 
  private final NUD[] nudA = new NUD[]{
    new NUD(new String[]{"Tws", "°C", "Temperatur"}, 0),
    new NUD(new String[]{"Hws", "%", "Luftfeuchte"},1),
    new NUD(new String[]{"Pws", "hPa", "Luftdruck"}, 2) 
  };

  private final DataWeatherClient owm = new UrlConnectionDataWeatherClient("51a804cefd09c6dfc973a35591b3d91a");
  private final CurrentWeatherOneLocationQuery query = QueryBuilderPicker.pick()
    .currentWeather() // get current weather
    .oneLocation() // for one location
    .byCityId("7873390")  // Furstenfeld (see OWMTest)
    //.byCityName("Furstenfeld")
    //.countryCode("AT")
    //.type(Type.ACCURATE) // with Accurate search (insteadof LIKE)
    .language(Language.ENGLISH) // in English language
    .responseFormat(ResponseFormat.JSON)// with JSON response format
    .unitFormat(UnitFormat.METRIC) // in metric units
    .build();

  // enable use of tail -f /var/log/tomcat8/catalina.out, used by PiEndpoint too
  private static final Logger LOG = PiHive.LOG;
  
  private Optional<CompletableFuture<CurrentWeather>> owmData = Optional.empty();
  
  
  public OWMStation(Map<String, Sensor> sensorMap) {
    super(sensorMap);
    super.setup(nudA, getClass());
  }
 
  /**
   * prepare optional future 
   */
  @Override
  public void trigger() {
    owmData = Optional.of(CompletableFuture.supplyAsync(() -> {
      return owm.getCurrentWeather(query);
    }));
    LOG.log(Level.FINE, "OWM async called");
  }
  
  @Override
  public boolean hasData() {
    if (owmData.isPresent()) {
      LOG.log(Level.FINE, "OWM data present");
      CompletableFuture<CurrentWeather> cf = owmData.get();
      if (cf.isDone()) {
        CurrentWeather cw;
        try {
          cw = cf.get();
          MainParameters mp = cw.getMainParameters();
          long ts = cw.getDateTime().getTime();
          for (String name : channels) {
            ExtSensor s = (ExtSensor)sensorMap.get(name);
            double value = Double.NaN;
            switch (s.getId()) {
              case "0": value = mp.getTemperature(); break;
              case "1": value = mp.getHumidity(); break;
              case "2": value = mp.getPressure(); break;
              default: LOG.log(Level.WARNING, "Invalid channel definition {0}", s);
            }
            s.setValue(new StampedNV(ts, name, value));
          }
          owmData = Optional.empty();
          return true; 
        } catch (InterruptedException | ExecutionException ex ) {
          LOG.log(Level.SEVERE, null, ex);
        }
      } 
    }
    return false;
  }
}
