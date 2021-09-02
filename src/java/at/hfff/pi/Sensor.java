package at.hfff.pi;

/**
 * Holder for sensor data including linear calibration
 * superclass members are persistent except "enabled" as there is no reenable method yet in socket interface
 * see toString() for output format
 * @author horst
 */
public abstract class Sensor {
  private String name = "";     // mandatory/unique for matching
  private String descr = "";    // e.g. where sensor shall be mounted, ....
  private String unit = "";     // descriptive, e.g. hPa or °C
  private String id = "";       // to identify e.g W1Devices
  private double a = 1.0;   // default calibration y = a*x + b;
  private double b = 0.0;   // --"-- 
  private double delta = 0.1;  // min delta (e.g. to be put into history log)
  private final long delay = 60000;  // 60 seconds (non yet configureable, just for temperatures)
  private FType check = FType.ANY;       // accept any value, see history.filter
  private boolean enabled = true;  // gather values, false e.g. when sensor fails
  private StampedNV snvLast = null;   // value of last acceptet measurement 
   
  private double setValue = Double.NaN;  // value when last tare
 
  private final double MINTARA = 5.0;  // minimum weight addon to be accepted as second point
  private final static String SEP = ", ";
  
  protected enum FType {ANY, CHANGED, NONZERO, MINDIFF, MAXDIFF, MINDELAY, MINDIFFDELAY}
  
  /**
   * @param params String array holding name unit description class [id enabled [a b delta ftyp]]
   */
  public Sensor(String[] params) {
    name = params[0];
    unit = params[1];
    descr = params[2];
    
    // params[3] == getClass.getName() used to restore proper subclass
    if (params.length > 5) {
      id = params[4];
      enabled = Boolean.parseBoolean(params[5]);
      if (params.length > 7) {
        a = Double.parseDouble(params[6]);
        b = Double.parseDouble(params[7]);
        if (params.length > 9) {
          delta = Double.parseDouble(params[8]);
          check = FType.valueOf(params[9]);
        }
      }
    }
  }
  
  /**
   * @return the name
   */
  public String getName() {
    return name;
  }

  /**
   * @param name the name to set
   */
  public void setName(String name) {
    this.name = name;
  }

 
  /**
   * @return the descr
   */
  public String getDescr() {
    return descr;
  }

  /**
   * @param descr the descr to set
   */
  public void setDescr(String descr) {
    this.descr = descr;
  }
  
  /**
   * @return the unit
   */
  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getId() {
    return id;
  }

  public void setId (String id) {
    this.id = id;
  }
  
  public void setCalibration(double a, double b) {
    this.a = a;
    this.b = b;
  }
  
  /**
   * Calculate calibration
   * when setValue from preceeding setTare() is significant higher than new setValue
   * both linear coefficients are calculated, otherwise only offset (b) is adapted to fullfill 
   * @param setValue is the value which should be shown instead of curValue  
   * @param curValue is the current value scaled with old coefficients  
   * @return true if offset has been set, false when slope has been calculated without keeping tare settings
   */
  public boolean setTare(double setValue, double curValue) {
    if ((setValue - this.setValue) > MINTARA) {
      // significant weight has been added to calculate slope (delta == 0.1 -> 5kg minimum)
      // calculate a and b using current a and b, old and new setvalue and current value;
      double corr = (this.setValue - setValue)/(this.setValue - curValue);
      a = corr * a;
      b = setValue - corr * (curValue - b); 
      return false; 
    }
    // just set offset
    b += setValue - curValue;
    this.setValue = setValue;
    return true;
  }
  
  public void setCeck(FType check) {
    this.check = check;
  }
  
  public FType getCheck() {
    return check;
  }
  
  public void setDelta(double delta) {
    this.delta = delta;
  }
  
  public double getDelta() {
    return delta;
  }
  
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
  
  public boolean isEnabled() {
    return enabled;
  }
  
  public void setLast(StampedNV snv) {
    snvLast = snv;
  }
  
  public StampedNV getLast() {
    return snvLast;
  }
  
  // depends on subclass 
  public abstract StampedNV getValue();
  
  // report subclass
  public abstract String getType();
  
  public double calibrate(double value) {
    return value * a + b;
  }
  
  protected boolean isValid(StampedNV snv) {
    boolean valid = snv != null && snv.value != null;  // null's are passed thru
    if (valid && snvLast != null) { // others are passed thru if no last value yet
      switch (check) {
        case ANY:  // add unconditionally
          break;
        case CHANGED:   // add if value changed
          valid = !snvLast.value.equals(snv.value);
          break;
        case NONZERO:   // add if nonzero or changed 
          valid = !snvLast.value.equals(snv.value);
          if (snv.value instanceof Number) {
            valid |= ((Number) snv.value).doubleValue() != 0.0;
          }
          break;
        case MINDIFF:   // add if value change higher than delta
          if (snv.value instanceof Number) {
            valid = Math.abs((double) snv.value - (double) snvLast.value) >= delta;
          }
          break;
        case MAXDIFF:   // add if value change lower than delta
          if (snv.value instanceof Number) {
            valid = Math.abs((double) snv.value - (double) snvLast.value) < delta;
          }
          break;
        case MINDIFFDELAY:  // add if more than delta later
          valid = (snv.pTime - snvLast.pTime) > delay;
          if (valid && snv.value instanceof Number)
            valid = Math.abs((double) snv.value - (double) snvLast.value) >= delta;
          break;
          
      }
    }

    if (valid)
      snvLast = snv;
    return valid;
  }
  
  protected StampedNV checked(StampedNV snv) {
    return isValid(snv) ? snv : null;
  }

  /**
   * 
   * @return single line configuration 
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(name).append(SEP)
      .append(unit).append(SEP)
      .append(descr).append(SEP)
      .append(getType()).append(SEP)
      .append(id).append(SEP)
      .append(Boolean.toString(enabled)).append(SEP)
      .append(a).append(SEP)
      .append(b).append(SEP)
      .append(delta).append(SEP)
      .append(check);
    return sb.toString();
  }
}
