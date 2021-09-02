/* $Id: CView.java,v 1.4 2019/08/14 20:58:07 horst Exp $ */
package at.hfff.pi;

import java.awt.Color;
import java.io.Serializable;
import org.jfree.data.time.TimeSeries;

/**
 * No getters/setter yet 
 * TODO: separate runtime elements (data, lastvalue) as they will not persist 
 *
 * @author horst
 */
public class CView implements Serializable {
  // required, serialized data same, but classes differ
  static final long serialVersionUID = 7949037350784775515l;
  String name;
  Color color; // graph color
  int axis;   // to which axis series applies, first always 0 
  boolean isBinary; // square waves or 
  boolean isVisible;  // not yet used for server created views!
  transient TimeSeries data;
  transient double lastValue;

  // invisible default
  CView(String name) {
    this(name, Color.BLACK, 0, false, false);
  }
  // typically from persistent, visible
  CView(String name, Color color, int axis, boolean isBinary) {
    this(name, color, axis, isBinary, true);
  }
  // full
  CView(String name, Color color, int axis, boolean isBinary, boolean isVisible) {
    this.name = name;
    this.color = color;
    this.axis = axis;
    this.isBinary = isBinary;
    this.isVisible = isVisible;
  }
  
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(name).append(',').append(color).append(',').append(axis).append(',').append(isBinary).append(',').append(isVisible);
    return sb.toString();
  }
}
