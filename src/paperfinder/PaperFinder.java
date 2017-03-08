package paperfinder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
	private boolean initialized = false; //A sentinel value to ensure initialization was performed correctly

    /**
     * @throws ServletException 
     * @see HttpServlet#HttpServlet()
     */
	public void init(ServletConfig config) throws ServletException
	{
		super.init(config);
		
        //The lucene object caching is performed here in the constructor.  This means that queries can all use the same objects, rather than having to construct new ones.
        //This improves performance drastically.
        try {
            //String index = "citeseer2_index";
        	String index = "sigmod_vldb_icse_index";
	    	reader = DirectoryReader.open(FSDirectory.open(Paths.get(getServletContext().getRealPath(index))));
	        searcher = new IndexSearcher(reader);
	        analyzer = new StandardAnalyzer();
	        //parser = new QueryParser("contents", analyzer);
	        parser = new QueryParser("title", analyzer);
	        
	        //System.out.println("got this far at least");
	        String rPathDir = getServletContext().getRealPath("spellcheck");
	        String rPathWords = getServletContext().getRealPath("words.txt");

	        spellcheck = new SpellChecker(FSDirectory.open(Paths.get(rPathDir)));

	        spellcheck.indexDictionary(new PlainTextDictionary(Paths.get(rPathWords)), new IndexWriterConfig(), true); //TODO: should change analyzer?
	        
	        SortField sf = new SortField("pageRank", SortField.Type.LONG);
	        sf.setMissingValue(Long.MAX_VALUE); //missing values should appear last
	        prSort = new Sort(sf, SortField.FIELD_SCORE);
	        
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
	    	
	        Query query = parser.parse(paramQuery);
	        Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter("<highlight>", "</highlight>"), new QueryScorer(query));
	        TopDocs results = searcher.search(query, reader.numDocs(), prSort);
	        
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
	        
	        int pages = results.scoreDocs.length / pageSize + 1;
	        out.println("<pages><current>" + page + "</current><last>" + pages + "</last></pages>");
	    
	        
	        out.println("<results>");
	        
	        int start = page*pageSize;
	        int end = (page+1)*pageSize;
	        if (end > results.scoreDocs.length)
	        {
	        	end = results.scoreDocs.length;
	        }
	        
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
	                String pageRank = doc.get("pageRank");
	                if (pageRank != null) {
	                	out.println("\t\t<pagerank>" + pageRank + "</pagerank>");
	                }
	                String PRcomponent = doc.get("pageRankComponent");
	                if (PRcomponent != null) {
	                	out.println("\t\t<pagerankraw>" + PRcomponent + "</pagerankraw>");
	                }
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
