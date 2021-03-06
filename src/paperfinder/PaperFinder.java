package paperfinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.apache.lucene.analysis.standard.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.spell.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.commons.io.*;
import org.apache.commons.lang3.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.index.memory.MemoryIndex;

class MultinomialNaiveBayesClassifier {
	public MultinomialNaiveBayesClassifier(Path path) throws IOException
	{
		//Load classifier information from file
		
        BufferedReader stream = new BufferedReader(new InputStreamReader(Files.newInputStream(path)));
        
        //File format:
        /*
         * Line: N (integer) -- number of terms
         * Line: C (integer) -- number of classes
         * Line(N): terms 
         * Line(C): class names
         * Line: priors[1..N] -- prior probabilities, space delimited (pre logged)
         * Line--onwards: condProb[C][t] -- condProbs of classes, one per line (pre logged)
         */
        
        String N_raw = stream.readLine();
        N = Integer.parseInt(N_raw);
        String C_raw = stream.readLine();
        C = Integer.parseInt(C_raw);
        
        
        V = new String[N];
        classNames = new String[C];
        prior = new double[C];
        condProb = new double[C][N];
        
        for (int i = 0; i < N; i++)
        {
        	String v_raw = stream.readLine();
        	V[i] = v_raw;
        }
        
        for (int i = 0; i < C; i++)
        {
        	String c_raw = stream.readLine();
        	classNames[i] = (c_raw);
        }
        
        String[] priors_raw = stream.readLine().split("\\s");
        for (int i = 0; i < C; i++)
        {
        	prior[i] = Double.parseDouble(priors_raw[i]);
        }
        
        for (int i = 0; i < C; i++)
        {
        	String[] condProb_raw = stream.readLine().split("\\s");
            for (int j = 0; j < N; j++)
            {
            	condProb[i][j] = Double.parseDouble(condProb_raw[j]);
            }
        }
        
	}
	

	public String classify(String title) {
		
		String[] tokens = title.replaceAll("[^a-zA-Z ]", " ").toLowerCase().split("\\s+");

		//String[] tokens = title.split("\\s");
		
		double scores[] = new double[C];
		
		for (int i = 0; i < C; i++)
		{
			scores[i] = prior[i];
		}
		
		for (int i = 0; i < tokens.length; i++)
		{
			String token = tokens[i];
			
			int index = 0;
			while (index < V.length)
			{
				if (token.equals(V[index]))
				{
					break;
				}
				index++;
			}
			
			if (index == V.length)
			{
				//Skip this token -- do not factor it into calculation
				continue;
			}
			
			for (int j = 0; j < C; j++)
			{
				scores[j] += condProb[j][index];
			}
		}
		
		double maxScore = Integer.MIN_VALUE;
		int maxIndex = Integer.MIN_VALUE;
		for (int i = 0; i < C; i++)
		{
			if (maxScore < scores[i])
			{
				maxScore = scores[i];
				maxIndex = i;
			}
		}
		
		return classNames[maxIndex];
		
	}
	
	private int N;
	private int C;
	private String[] V;
	private String[] classNames;
	private double[] prior;
	private double[][] condProb;
}

/**
 * Servlet implementation class PaperFinder
 * 
 * This servlet is the backend of PaperFinder.  It accepts queries and returns results in XML format.
 */
@WebServlet(asyncSupported = true, urlPatterns = { "/PaperFinder" })
public class PaperFinder extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final int pageSize = 10; //Unused, but will become useful in the future so that we can return reasonable sized responses to the user
	
	//Cached Lucene objects:
	private IndexReader reader;
	private IndexSearcher searcher;
	private Analyzer analyzer;
	private QueryParser parser;
	private SpellChecker spellcheck;
	private Sort prSort;
	private MultinomialNaiveBayesClassifier classifier;
	private boolean initialized = false; //A sentinel value to ensure initialization was performed correctly

    /**
     * @throws ServletException 
     * @see HttpServlet#HttpServlet()
     */
	
	@Override
	public void init(ServletConfig config) throws ServletException {
	    super.init(config); 
        //The lucene object caching is performed here in the constructor.  This means that queries can all use the same objects, rather than having to construct new ones.
        //This improves performance drastically.
        try {
            //String index = "citeseer2_index";
        	String index = "sigmod_vldb_icse_index";
	    	//reader = DirectoryReader.open(FSDirectory.open(Paths.get(getServletContext().getRealPath(index))));
        	reader = DirectoryReader.open(FSDirectory.open(Paths.get(index)));
	        searcher = new IndexSearcher(reader);
	        analyzer = new StandardAnalyzer();
	        //parser = new QueryParser("contents", analyzer);
	        parser = new QueryParser("title", analyzer);
	        
	        //System.out.println("got this far at least");
	        //String rPathDir = getServletContext().getRealPath("spellcheck");
	        String rPathDir = "spellcheck";
	        //String rPathWords = getServletContext().getRealPath("words.txt");
	        String rPathWords = "words.txt";

	        spellcheck = new SpellChecker(FSDirectory.open(Paths.get(rPathDir)));

	        spellcheck.indexDictionary(new PlainTextDictionary(Paths.get(rPathWords)), new IndexWriterConfig(), true); //TODO: should change analyzer?
	        
	        SortField sf = new SortField("pageRankComponent", SortField.Type.DOUBLE, true);
	        sf.setMissingValue(Double.NEGATIVE_INFINITY); //missing values should appear last
	        prSort = new Sort(sf, SortField.FIELD_SCORE);
	        
	        classifier = new MultinomialNaiveBayesClassifier(Paths.get("classifier.dat"));
	        
	        initialized = true;
        } catch (Exception e) {
        	System.out.println("Exception: " + e.getMessage()); //Will be printed to Tomcat console
        }
	}
	
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 * 
	 * Implements the GET request.  Accepts one argument, "query", which is the query string.
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		//response.getWriter().append("Served at: ").append(request.getContextPath());
		response.setContentType("text/xml;charset=UTF-8");
		//response.setCharacterEncoding("UTF-8");
		PrintWriter out = response.getWriter();
		
		//Results are UTF-8 formatted XML
		out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		
		if (initialized == false) {
			out.println("<error>Initialization failed!  Check server logs for details</error>");
			return;
		}
		
		out.println("<search>");
		

        try {
	    	String paramQuery = request.getParameter("query");
	    	if (paramQuery == null) {
	    		out.println("<error>No query provided</error>");
	    		return;
	    	}
	    	
	    	String paramPage = request.getParameter("page");
	    	int page = 0;
	    	if (paramPage != null) {
	    		page = Integer.parseInt(paramPage);
	    		if (page < 0)
	    		{
	    			page = 0;
	    		}
	    	}
	    	
	    	String paramCluster = request.getParameter("cluster");
	    	Query query;
	    	if (paramCluster == null)
	    	{
	    		query = parser.parse(paramQuery);
	    	}
	    	else
	    	{
	    		query = parser.parse("cluster:" + paramCluster + " AND title:" + paramQuery);
	    	}
	        Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter("<highlight>", "</highlight>"), new QueryScorer(query));
	        TopDocs results = searcher.search(query, reader.numDocs(), prSort, true, true);
	        
	        //out.println(results.totalHits + " total matching documents");
	        out.println("<total>" + results.totalHits + "</total>");
	        
	        if (results.totalHits == 0)
	        {

	        	String[] suggestions = spellcheck.suggestSimilar(paramQuery, 1);
	        	if (suggestions.length > 0)
	        	{
			        out.println("<suggestion>");
			        out.println(suggestions[0].trim());
			        out.println("</suggestion>");
	        	}

	        }
	        
	        int pages = results.scoreDocs.length / pageSize;
	        out.println("<pages><current>" + page + "</current><last>" + pages + "</last></pages>");
	    
	        Map<String, Integer> clusterSet = new HashMap<String, Integer>();
	        for (int i = 0; i < results.scoreDocs.length; i++) {
	        	Document doc = searcher.doc(results.scoreDocs[i].doc);
	        	String cluster = doc.get("cluster");
	        	Integer count = clusterSet.get(cluster);
	        	if (count == null)
	        	{
	        		count = 1;
	        	}
	        	else
	        	{
	        		count++;
	        	}
	        	clusterSet.put(cluster, count);
	        }
	        
	        out.println("<clusters>");
	        for (Map.Entry<String, Integer> entry : clusterSet.entrySet())
	        {
	        	String s = entry.getKey();
	        	Integer sz = entry.getValue();
	        	out.println("<cluster><name>" + s + "</name><size>" + sz + "</size></cluster>");
	        }
	        out.println("</clusters>");
	        
	        out.println("<results>");
	        
	        int start = page*pageSize;
	        int end = (page+1)*pageSize;
	        if (end > results.scoreDocs.length)
	        {
	        	end = results.scoreDocs.length;
	        }
	        DecimalFormat df = new DecimalFormat("#.#################");
	        for (int i = start; i < end; i++) { //Print ALL results, sorted, rather than only first n.
	                Document doc = searcher.doc(results.scoreDocs[i].doc);
	                //String path = doc.get("path");
	                //out.println((i + 1) + ". " + path);
	                out.println("\t<result>");
	                String title = doc.get("title");
	                if (title != null) {
	                        //out.println("   Title: " + doc.get("title"));
	                	out.println("\t\t<title>" + StringEscapeUtils.escapeXml10(title) + "</title>");
	                }
	                String conference = doc.get("conference");
	                if (conference != null) {
	                	out.println("\t\t<conference>" + conference + "</conference>");
	                }
	                /*String pageRank = doc.get("pageRank");
	                if (pageRank != null) {
	                	out.println("\t\t<pagerank>" + pageRank + "</pagerank>");
	                }*/
	                String PRcomponent = doc.get("pageRankComponent");
	                if (PRcomponent != null) {
	                	String c = df.format(Double.parseDouble(PRcomponent));
	                	out.println("\t\t<pagerankraw>" + c + "</pagerankraw>");
	                }
	                float relevance = results.scoreDocs[i].score;
	                out.println("\t\t<relevance>" + df.format(relevance) + "</relevance>");
	                
	                String predicted = classifier.classify(title);
	                out.println("\t\t<predicted>" + predicted + "</predicted>");
	                
	                String cluster = doc.get("cluster");
	                out.println("\t\t<cluster>" + cluster + "</cluster>");
	                //InputStream stream = Files.newInputStream(Paths.get(getServletContext().getRealPath(path)));
	                //String contents = IOUtils.toString(stream, StandardCharsets.UTF_8);
	                // String contents = doc.get("contents");
	                //TODO: accelerate with pre-generated term vectors?
	                /*if (contents != null)
	                {
	                	String xmlContents =  StringEscapeUtils.escapeXml10(contents);
		                TokenStream tokenStream = TokenSources.getTokenStream("content", null, xmlContents, analyzer,  -1); //highlighter.getMaxDocCharsToAnalyze()
		                String context = highlighter.getBestFragments(tokenStream, xmlContents, 3, "...");
		                if (context != null)
		                {
		                	out.println("\t\t<context>\n" + context + "</context>");
		                }
	                }*/
	                //out.println("\t\t<fields>" + doc.getFields() + "</fields>");
	                //out.println("\t\t<path>" + path + "</path>");
	                out.println("\t</result>");
	        }
	        out.println("</results>");
        } catch (ParseException e) {
        	out.println("<error>Could not parse query!  Reason: " + e.getMessage() + "</error>");
        }/* catch (InvalidTokenOffsetsException e) {
			// TODO Auto-generated catch block
        	out.println("<error>Invalid token offsets!  Reason: " + e.getMessage() + "</error>");
		}*/ finally {
        	out.println("</search>");
        }

	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
	

}
