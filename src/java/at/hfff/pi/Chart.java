package at.hfff.pi;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.block.BlockBorder;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;
import org.jfree.chart.renderer.xy.StandardXYItemRenderer;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.HorizontalAlignment;
import org.jfree.data.time.FixedMillisecond;
import org.jfree.data.time.RegularTimePeriod;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.graphics2d.svg.SVGGraphics2D;

/**
 * for server side Chart create!
 * Different to client side (FHChart project) there is no interaction (value popup, modification of data)
 * as SVG is created and display within Webapp ("Vectorgraphics") or posted (published) periodically as svg file to bees homepage
 * 
 * @author horst
 */
public class Chart {  
  private static final Logger LOG = Logger.getLogger(Chart.class.getName());

  private final ArrayList<CView> cViewL;  
  private final ArrayList<TimeSeriesCollection> cSet = new ArrayList<>();
  
  private final JFreeChart chart;
  
  //fontNames e.g. "Bitstream Vera Sans" "Palatino" 
  private static final Font P14 =  new Font("Bitstream Vera Sans", Font.PLAIN, 14);
  private static final Font P10 =  P14.deriveFont(Font.PLAIN, 10);
  private static final Font P12 =  P14.deriveFont(Font.PLAIN, 12);
  
  /**
   *
   * @param title  typically the source of data
   * @param showCurrent when nonnull will show last values as subtitle
   * @param cViewL the channels to be shown
   */
  public Chart(String title, String showCurrent, ArrayList<CView> cViewL) {
    this.cViewL = cViewL;
   
    // initialize datasets
    int ds = 0;
    int cnt = 0;
    while (cnt < cViewL.size()) {   // all channels collected?
      TimeSeriesCollection timeSeries = new TimeSeriesCollection();
      for (CView cv : cViewL) {
        if (cv.axis == ds) {
          cv.data = new TimeSeries(cv.name);
          timeSeries.addSeries(cv.data);
          cnt++;
        }
      }
      cSet.add(timeSeries);
      ds++;
    }
    
    // setup chart panel   title, x axis description, y axis description, first dataset
    chart = ChartFactory.createTimeSeriesChart(title, null, null, cSet.get(0));

    chart.getTitle().setFont(P12);
    if (showCurrent != null) {
      TextTitle subtitle = new TextTitle(showCurrent);
      subtitle.setFont(P10);
      chart.addSubtitle(subtitle);
      //LOG.log(Level.INFO, "Subtitles: {0}", chart.getSubtitleCount());
    }

    XYPlot plot = (XYPlot) chart.getPlot();
    plot.setDomainPannable(true);
    plot.setRangePannable(false);
    plot.setDomainCrosshairVisible(true);
    plot.setRangeCrosshairVisible(true);
    plot.getDomainAxis().setLowerMargin(0.0);
    plot.getDomainAxis().setLabelFont(P12);
    plot.getDomainAxis().setTickLabelFont(P10);
    plot.getRangeAxis().setLabelFont(P12);
    plot.getRangeAxis().setTickLabelFont(P10);
    //plot.setBackgroundPaint(Color.lightGray);  // GRAY is default
    //plot.setRangeGridlinePaint(Color.GRAY);  // WHITE is default
    //plot.setDomainGridlinePaint(Color.GRAY);
    chart.getLegend().setItemFont(P10);
    chart.getLegend().setFrame(BlockBorder.NONE);
    chart.getLegend().setHorizontalAlignment(HorizontalAlignment.CENTER);
    XYItemRenderer r0 = plot.getRenderer();
    if (r0 instanceof XYLineAndShapeRenderer) {
      XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) r0;
      renderer.setDefaultShapesVisible(false);
      renderer.setDrawSeriesLineAsPath(true);
      // set the default stroke for all series
      renderer.setAutoPopulateSeriesStroke(false);
      renderer.setDefaultStroke(new BasicStroke(1.0f,
        BasicStroke.CAP_ROUND, BasicStroke.JOIN_BEVEL), false);
    }
    XYToolTipGenerator ttg = r0.getDefaultToolTipGenerator();  // to reuse for other renderers
   
    // process all collections
    int di = 0; // dataset index
    for (TimeSeriesCollection dSet : cSet) {
      int tsCnt = dSet.getSeriesCount();
      NumberAxis axis = null;
      XYItemRenderer renderer = null;
      for (int ti = 0; ti < tsCnt; ti++) {
        TimeSeries ts = dSet.getSeries(ti);
        for (CView cv: cViewL) {
          if (cv.isVisible && ts == cv.data) {
            cv.lastValue = Double.NaN;
            if (di == 0) {
              // default domain  from first TimerSeriesCollection
              plot.getRenderer().setSeriesPaint(ti, cv.color);
            } else {      
              if (tsCnt == 1) {  // only if only one series,otherwise default black
                if (axis == null) {
                  axis = new NumberAxis();
                  //axis.setFixedDimension(10.0);   // would make rightmost axis invisible???
                  axis.setAutoRangeIncludesZero(false);  // scale using 0 included, default true
                  plot.setDataset(di, dSet);
                  plot.setRangeAxis(di, axis);
                  plot.mapDatasetToRangeAxis(di, di);  // dataset - axis
                } 
                axis.setLabelPaint(cv.color);
                axis.setLabelFont(P10);
                axis.setTickLabelPaint(cv.color);
                axis.setTickLabelFont(P10);
              }
              if (renderer == null) {
                //TODO: restructure combined plot, add plot type to cview (xy, bar, ...) to avoid "by name" hook
                if (cv.name.equals("Nws")) {
                  // WARNING: rain per hour, but often data fetch fails, add indicator (color) for missing values
                  // turn off most features, e.g. gradient , shadow, outline
                  XYBarRenderer r = new XYBarRenderer();  // no trimming
                  r.setShadowVisible(false);
                  r.setDrawBarOutline(false);
                  r.setBarPainter(new StandardXYBarPainter());
                  renderer = r;
                } else
                  renderer = new StandardXYItemRenderer();   // no popup legends
                
                //renderer = new XYLineAndShapeRenderer();  // draws thick nodes (when not configured to avoid that)
                //renderer = new HighLowRenderer();  // draws x/y crosshair to scale when x is selected 
                plot.setRenderer(di, renderer);
              }
              renderer.setDefaultToolTipGenerator(ttg);
              renderer.setSeriesPaint(ti, cv.color);
            }
          }
        }
      }
      di++;
    }
  }

  /**
   * Add Events (StampedNV fields) e.g. when adding to history buffer.
   * See CChart (from PiHiveClient app) for scanning text at client side
   *
   * @param cTime
   * @param cName
   * @param cValue (only numbers accepted!)
   */
  public void appendData(long cTime, String cName, Object cValue) {
    LOG.log(Level.FINE, "Name: {0} Date: {1} Value: {2}", new Object[]{cName, cTime, cValue});
    if (cValue instanceof Number) {
      double value = ((Number) cValue).doubleValue();
      cViewL.stream().filter((cv) -> (cv.isVisible && cv.name.equalsIgnoreCase(cName))).map((cv) -> {
        RegularTimePeriod curr = new FixedMillisecond(cTime);
        if (cv.isBinary) {
          // Pump and Pressure are digital 0-1 signals, extra point "befor-state" needed to draw signal
          // cannot use SimpleTimePeriod here, TimeSeries requires RegularTimePeriod
          RegularTimePeriod befor = curr.previous(); // 1 ms less
          
          // might already exist, therefor addOrUpdate()
          cv.data.addOrUpdate(befor, value);
          cv.data.addOrUpdate(curr, 1.0 - value); // inverted, value = 1 means off aka 0
        } else {
          cv.data.addOrUpdate(curr, value);
        }
        return cv;
      }).forEachOrdered((cv) -> {
        cv.lastValue = value;
      });
    }
  }

  private void resetData() {
    cViewL.forEach((cv) -> {
      cv.data.clear();
    });
  }

  public String getSVG(int w, int h) {
    // more than legend only?
    if (chart.getSubtitleCount() > 1) {
      // add last values
      TextTitle subtitle = (TextTitle)chart.getSubtitle(1);
      StringBuilder sb = new StringBuilder();
      Formatter sbf = new Formatter(sb);
      sb.append(subtitle.getText());
      cViewL.forEach((cv) -> {
        sbf.format("%1$s %2$.1f , ", cv.name, cv.lastValue);
      });
      subtitle.setText(sb.substring(0, sb.length() - 2));
    }
    
    // setup  for SVG output
    SVGGraphics2D g2 = new SVGGraphics2D(w, h);
    g2.setRenderingHint(JFreeChart.KEY_SUPPRESS_SHADOW_GENERATION, true);
    chart.draw(g2, new Rectangle(0, 0, w, h));
    return g2.getSVGElement();
  }
}
