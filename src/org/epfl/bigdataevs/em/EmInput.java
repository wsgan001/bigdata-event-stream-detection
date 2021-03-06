package org.epfl.bigdataevs.em;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.fraction.BigFraction;
import org.apache.commons.math3.fraction.Fraction;
import org.epfl.bigdataevs.executables.Parameters;
import org.epfl.bigdataevs.input.ParsedArticle;
import org.epfl.bigdataevs.input.TimePartition;
import org.epfl.bigdataevs.input.TimePeriod;

import scala.Tuple2;

/**
 * Team Antoine & Nina
 * 
 * @author Antoine
 * 
 * Representation of an input of the EM algorithm within one TimePartition.
 * 
 * Contain the background model (common to every EmInput), 
 * the set of documents within de time partition
 * and the themes within de time partition. 
 * 
 **/

public class EmInput implements Serializable {
  /** Map containing tuples of words and their 
   * distribution in the streams. **/
  public Map<String, Double> backgroundModel;
  /** Collection containing articles published at that time. **/
  public Collection<Document> documents;
  
  /** List of the themes appearing in this input*/
  public ArrayList<Theme> themesOfPartition;
  
  /** Index of the EmInput in the RDD**/
  public Long indexOfPartition = 0L;
  
  /** Index of the run for this EmInput**/
  public int run;
  
  /** Time period containing all articles**/
  public TimePeriod timePeriod;
  
  /** Number of iterations that the EM Algorithm processed in this EmInput**/
  public int numberOfIterations = 0;
  
  /**
   * EmInput contains at least the background model, 
   * the list of articles and the period delimiting these articles.
   * @param backgroundModel the background model probabilitic distribution
   * @param documents the set of articles
   * @param period the time period containing the articles
   */
  public EmInput(Map<String, Double> backgroundModel,
          Collection<Document> documents, TimePeriod period) {
    
    this.backgroundModel = backgroundModel;
    this.documents = documents;
    this.timePeriod = period;
    this.themesOfPartition = new ArrayList<>();
  }
  
  /**
   * EmInput builds with an instance of TimePartition
   * @param backgroundModel the background model probabilitic distribution
   * @param timePartition the time partition containing time periods and articles
   */ 
  public EmInput(TimePartition timePartition, Map<String, BigFraction> backgroundModel) {
    this.timePeriod = timePartition.timePeriod;
    this.themesOfPartition = new ArrayList<>();
    this.backgroundModel = new HashMap<String, Double>();
    List<Document> convertedDocuments = new ArrayList<Document>();
    for (ParsedArticle article : timePartition.parsedArticles) {
      convertedDocuments.add(new Document(article));
    }
    this.documents = convertedDocuments;
    for (String word : backgroundModel.keySet()) {
      this.backgroundModel.put(word, backgroundModel.get(word).doubleValue());
    }
  }
  
  
  public void addTheme(Theme theme) {
    this.themesOfPartition.add(theme);
  }
  
  /**
   * Initialize all probabilities in the articles (article d belongs to theme j)
   */
  public void initializeArticlesProbabilities() {
    for (Document article : this.documents) {
      article.initializeProbabilities(themesOfPartition);
    }
  }
  
  /**
   * Compute the log-likelihood of the mixture model
   * @return log-likelohood
   */
  public double computeLogLikelihood(double lambdaBackgroundModel) {
    double logLikelihood = 0.0;
    for (Document article : documents) {
      for (String word : article.words.keySet()) {
        double temp = 0.0;
        for (Theme theme : themesOfPartition) {
          temp = temp + (article.probabilitiesDocumentBelongsToThemes.get(theme)
                  * theme.wordsProbability.get(word));
        }
        logLikelihood += (article.words.get(word) * Math.log(
                (lambdaBackgroundModel * backgroundModel.get(word))
                + ((1.0 - lambdaBackgroundModel) * temp)))
                / article.words.size();
      }
    }
    return logLikelihood/documents.size();
  }
  
  /**
   * Update probabilities word belongs to theme
   */
  public Double subUpdateProbabilitiesOfWordsGivenTheme(String word, Theme theme) {
    double value  = 0.0;
    for (Document article : this.documents) {
      if (article.words.containsKey(word)) {
        value = value + (((double) article.words.get(word))
                * (1.0 - article.probabilitiesHiddenVariablesBackgroundModel.get(word)) 
                        * (article.probabilitiesHiddenVariablesThemes.get(
                                Pair.of(word, theme))));
      }
    }
    return value;
  }
  
  /**
   * Update the probabilities that words belongs to themes
   * Do the computation for every themes
   */
  public void updateProbabilitiesOfWordsGivenTheme(ArrayList<Theme> themes) {
   
    for (Theme theme : themes) {
      double denominator = 0.0;
      for (String word : theme.wordsProbability.keySet()) {
        denominator = denominator + subUpdateProbabilitiesOfWordsGivenTheme(word, theme);
      }
      for (String word : theme.wordsProbability.keySet()) {
        double numerator = subUpdateProbabilitiesOfWordsGivenTheme(word, theme);
        theme.wordsProbability.put(word, numerator / (denominator));
      }
    }
    
  }
  
  /**
   * Clone the EmInput by replacing every article.
   */
  public EmInput clone() {
    Collection<Document> articles = new ArrayList<>();
    for (Document article : this.documents) {
      articles.add(new Document(article.words, article.stream, article.title));
    }
    return new EmInput(this.backgroundModel, articles, this.timePeriod);
  }
  
  /**
   * Return the pair of themes for each EmInputs which them corresponding score.
   * @return
   */
  public Iterable<Tuple2<Theme, Double>> relatedThemes() {
    List<Tuple2<Theme, Double>> themesWithAverageProbability = new ArrayList<>();                  
    for (Theme theme : this.themesOfPartition) {
      double sum = 0.0;
      
      for (Document article : this.documents) {
        sum += article.probabilitiesDocumentBelongsToThemes.get(theme);
      }
      
      double average = sum / (double)this.documents.size();
      themesWithAverageProbability.add(new Tuple2<Theme, Double>(theme, average));
    }
    return (Iterable<Tuple2<Theme, Double>>) themesWithAverageProbability;
  }
  
  /**
   * Return the filtered pair of themes for each EmInputs which them corresponding score
   * @return
   */
  public Iterable<Tuple2<Theme, Double>> relatedFileteredThemes() {
    List<Tuple2<Theme, Double>> themesWithAverageProbability = new ArrayList<>();                  
    for (Theme theme : this.themesOfPartition) {
      double sum = 0.0;
      
      for (Document article : this.documents) {
        sum += article.probabilitiesDocumentBelongsToThemes.get(theme);
      }
      
      double average = sum / (double)this.documents.size();
      themesWithAverageProbability.add(new Tuple2<Theme, Double>(theme, average));
    }
    return (Iterable<Tuple2<Theme, Double>>) filterTheme(themesWithAverageProbability);
  }
  
  
  /**
   * Sort all articles in a theme by the probability that an article belongs to this theme.
   * Apply it to all themes
   */
  public void sortArticlesByScore() {
    for (Theme theme : this.themesOfPartition) {
      Map<Document, Double> articlesToThemes = new HashMap<Document, Double>();
      for (Document article : this.documents) {
        articlesToThemes.put(
                article, article.probabilitiesDocumentBelongsToThemes.get(theme));
      }
      TreeMap<Document, Double> sortedMap = new TreeMap<>(
              new ValueComparator(articlesToThemes));
      sortedMap.putAll(articlesToThemes);
      List<Document> highestProbArticles = new ArrayList<>();
      for (Document document : sortedMap.keySet()) {
        highestProbArticles.add(document);
      }
      theme.sortedArticlesByScore = highestProbArticles;
    }
  }
  
  /**
   * Comparator to sort the articles according to their probability in decreasing order.
   * @author abastien
   *
   */
  class ValueComparator implements Comparator<Document> {

    Map<Document, Double> base;
    
    public ValueComparator(Map<Document, Double> base) {
      this.base = base;
    }

    // Note: this comparator imposes orderings that are inconsistent with equals.    
    public int compare(Document a, Document b) {
      if (base.get(a).compareTo(base.get(b)) == 1) {
        return -1;
      } else {
        return 1;
      } // returning 0 would merge keys
    }
  }
  
  /**
   * Remove themes in this EmInput that don't statisfy themesWithScore
   * @param themesWithScore the proportion the threhold is accroding to 
   * the naive average of themes (ex: 1/10 if there are 10 themes)
   * @return a list of themes with their score
   */
  public List<Tuple2<Theme, Double>> filterTheme(List<Tuple2<Theme, Double>> themesWithScore) {
    double threshold = (1.0 / this.themesOfPartition.size()) * Parameters.themeFilteringThreshold;
    List<Tuple2<Theme, Double>> newThemesWithScores = new ArrayList<Tuple2<Theme,Double>>();
    for (Tuple2<Theme, Double> tuple : themesWithScore) {
      if (tuple._2 > threshold) {
        newThemesWithScores.add(tuple);
      }
    }
    return newThemesWithScores;
  }
}
