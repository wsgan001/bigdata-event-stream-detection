package org.epfl.bigdataevs.eminput;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;

import java.io.IOException;
import java.text.ParseException;
import java.util.LinkedList;
import java.util.List;

import javax.xml.stream.XMLStreamException;


/**Team: Matias and Christian.
*InputParser: parses the data read from HDFS, clean them, 
*and computes the background model for the EM algorithm
**/
public class InputParser {
 
  /** Parses data for all streams, cleans it and returns
    * an EmInput instance containing all the desired content
    * (background model and word distribution for every stream) for
    * the given time period (or frame).
    * @timePeriod the time interval that will be considered 
    *     for the streams.
    * @return container for the background model and word 
    *     count of every individual article in each stream
   * @throws IOException sometimes
   * @throws ParseException for numerous reasons
   * @throws XMLStreamException  for numerous cool other reasons
   * @throws NumberFormatException if a bad number was in the source xml
   **/
  public TextCollectionData getEmInput(List<TimePeriod> timePeriod,
                                                          SparkContext sparkContext) 
         throws NumberFormatException, XMLStreamException, ParseException, IOException {
    
    if (timePeriod == null) {
      return null; 
    }
    Configuration config = new Configuration();
    //Comment the next line if not using HDFS as source directory
    config.addResource(new Path("/usr/local/Cellar/hadoop/2.6.0/libexec/etc/hadoop/core-site.xml"));
    
    List<String> sourceList = new LinkedList<String>();
    sourceList.add("hdfs:///projects/dh-shared/JDG");
    
    TimePeriod englobingTimePeriod = TimePeriod.getEnglobingTimePeriod(timePeriod);
    
    RawArticleInputStream ras = new RawArticleInputStream(englobingTimePeriod, sourceList, config);
    
    RawArticle rawArticle;
    List<RawArticle> rawArticleList = new LinkedList<RawArticle>();
    
    while ((rawArticle = ras.read()) != null) {
      rawArticleList.add(rawArticle);
    }
    
    // Ca marche pas ça alors je commente pour pas break the build
    //JavaRDD rdd = sparkContext.parallelize(rawArticleList,1, RawArticle.class);
    
    
    return null;
  }
}