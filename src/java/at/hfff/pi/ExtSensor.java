/* $Id: ExtSensor.java,v 1.2 2019/11/12 15:54:26 horst Exp $ */
package at.hfff.pi;

/**
 * Dummy sensor holder, mainly to keep the configuration data 
 * general purpose typically for remote weather data
 * @author horst
 */
public class ExtSensor extends Sensor {
  private final Class sClass;
  private StampedNV snv;    // most recent valid value or null
  
  public ExtSensor(String[] params, Class sClass) {
    super(params);
    this.sClass = sClass;
  }

  @Override
  public String getType() {
    return sClass.getName();
  }
  
  // to keep verified result
  protected void setValue(StampedNV snv) {
    this.snv = checked(snv);
  }
  
  @Override
  public StampedNV getValue() {
    return snv;
  }
}
