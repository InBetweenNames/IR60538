package paperfinder;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.nio.file.Paths;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.commons.lang3.*;
/**
 * Servlet implementation class PaperFinder
 * 
 * This servlet is the backend of PaperFinder.  It accepts queries and returns results in XML format.
 */
@WebServlet(asyncSupported = true, urlPatterns = { "/PaperFinder" })
public class PaperFinder extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final int pageSize = 1000; //Unused, but will become useful in the future so that we can return reasonable sized responses to the user
	
	//Cached Lucene objects:
	private IndexReader reader;
	private IndexSearcher searcher;
	private Analyzer analyzer;
	private QueryParser parser;
	private boolean initialized = false; //A sentinel value to ensure initialization was performed correctly

    /**
     * @see HttpServlet#HttpServlet()
     */
    public PaperFinder() {
        super();
        
        //The lucene object caching is performed here in the constructor.  This means that queries can all use the same objects, rather than having to construct new ones.
        //This improves performance drastically.
        try {
            String index = "citeseer2_index";
	    	reader = DirectoryReader.open(FSDirectory.open(Paths.get(index)));
	        searcher = new IndexSearcher(reader);
	        analyzer = new StandardAnalyzer();
	        parser = new QueryParser("contents", analyzer);
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
	    	/*String paramPage = request.getParameter("page");
	    	int page = 0;
	    	if (paramPage != null) {
	    		page = Integer.parseInt(paramPage);
	    	}*/
	        Query query = parser.parse(paramQuery);
	        
	        TopDocs results = searcher.search(query, reader.numDocs());
	        
	        //out.println(results.totalHits + " total matching documents");
	        out.println("<total>" + results.totalHits + "</total>");
	        out.println("<results>");
	        for (int i = 0; i < results.scoreDocs.length; i++) { //Print ALL results, sorted, rather than only first n.
	                Document doc = searcher.doc(results.scoreDocs[i].doc);
	                String path = doc.get("path");
	                //out.println((i + 1) + ". " + path);
	                out.println("\t<result>");
	                String title = doc.get("title");
	                if (title != null) {
	                        //out.println("   Title: " + doc.get("title"));
	                	out.println("\t\t<title>" + StringEscapeUtils.escapeXml10(title) + "</title>");
	                }
	                out.println("\t\t<path>" + path + "</path>");
	                out.println("\t</result>");
	        }
	        out.println("</results>");
        } catch (ParseException e) {
        	out.println("<error>Could not parse query!  Reason: " + e.getMessage() + "</error>");
        } finally {
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
