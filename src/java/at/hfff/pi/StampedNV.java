 /*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package at.hfff.pi;

import java.text.SimpleDateFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Timestamped name-value tupples
 *
 * @author horst
 */
public class StampedNV implements Comparable {
  
  static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

  long pTime; // history entry creation time (when logged)
  String source;
  Object value;

  StampedNV(String source) {
    this(source, Optional.empty());
  }

  StampedNV(String source, Object value) {
    this(System.currentTimeMillis(), source, value);
  }

  StampedNV(long time, String source, Object value) {
    this.pTime = time;
    this.source = source;
    this.value = value;
  }

  @Override
  public String toString() {
    return new StringBuilder().append(SDF.format(pTime))
      .append('\t').append(source)
      .append('\t').append(value).toString();
  }

  @Override
  public int compareTo(Object o) {
    int compare = Long.compareUnsigned(pTime, ((StampedNV)o).pTime);
    if (compare == 0) {
      compare = source.compareTo(((StampedNV)o).source);
    }
    return compare;
  }
  
  /**
   * See https://bugs.openjdk.java.net/browse/JDK-8223933
   * Elements with same timestamp and name shall be equal independent of value, aka
   * on sort.distinct only one shall prevail
   * @return
   */
  @Override
  public int hashCode() {
    return Objects.hash(pTime, source);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final StampedNV other = (StampedNV) obj;
    if (this.pTime != other.pTime) {
      return false;
    }
    return source.equals(other.source);
  }
}
