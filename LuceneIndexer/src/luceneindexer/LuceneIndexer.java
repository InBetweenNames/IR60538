package luceneindexer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Hashtable;
import java.util.Vector;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
* Index all text files under a directory.
*/
public class LuceneIndexer {
	static int counter = 0;
	static Hashtable<Long, Vector<Long>> graph = new Hashtable<Long, Vector<Long>>();
	static Hashtable<Long, Double> pageRanksComponent = new Hashtable<Long, Double>();
	static Hashtable<Long, Long> pageRanks = new Hashtable<Long, Long>();
	static Hashtable<Long, String> clusters = new Hashtable<Long, String>();

	public static void main(String[] args) throws Exception {
		String indexPath = "sigmod_vldb_icse_index";
		String docsPath = "sigmod_vldb_icse/";
		System.out.println("Indexing to directory '" + indexPath + "'...");
		Directory dir = FSDirectory.open(Paths.get(indexPath));
		Analyzer analyzer = new StandardAnalyzer();
		IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
		IndexWriter writer = new IndexWriter(dir, iwc);
		
		loadSubgraph(Paths.get(docsPath + "sigmod_vldb_icse_subgraph.txt"));
		loadPageRanks(Paths.get(docsPath + "sigmod_vldb_icse.ranks"));
		loadClusters(Paths.get(docsPath + "clusters.txt"));
		indexMetadata(writer, Paths.get(docsPath + "icse_id.txt"), "ICSE");
		indexMetadata(writer, Paths.get(docsPath + "sigmod_id.txt"), "SIGMOD");
		indexMetadata(writer, Paths.get(docsPath + "vldb_id.txt"), "VLDB");
		
		writer.close();
	}
	
	static void loadSubgraph(Path graphFile) throws IOException
	{
		BufferedReader graphLines = new BufferedReader(new InputStreamReader(Files.newInputStream(graphFile)));
		
		String line;
		while ((line = graphLines.readLine()) != null)
		{
			int split = line.indexOf("\t");
			String paper1 = line.substring(0, split);
			String paper2 = line.substring(split + 1);
			Long paper1id = Long.parseLong(paper1);
			Long paper2id = Long.parseLong(paper2);
			
			try
			{
				Vector<Long> citations = graph.get(paper1id);
				citations.add(paper2id);
			}
			catch (NullPointerException e)
			{
				Vector<Long> citation = new Vector<Long>();
				citation.add(paper2id);
				graph.put(paper1id, citation);
			}

		}
		
	}
	
	static void loadPageRanks(Path ranksFile) throws IOException
	{
		BufferedReader rankLines = new BufferedReader(new InputStreamReader(Files.newInputStream(ranksFile)));
		
		String line;
		long count = 1;
		while ((line = rankLines.readLine()) != null)
		{
			int split = line.indexOf(" ");
			String sComponent = line.substring(0,  split);
			String sPaper = line.substring(split+1);
			Double rankComponent = Double.parseDouble(sComponent);
			Long paperid = Long.parseLong(sPaper);
			pageRanksComponent.put(paperid, rankComponent);
			pageRanks.put(paperid, count);
			count++;
		}
	}
	
	static void loadClusters(Path clusterFile) throws IOException
	{
		BufferedReader clusterLines = new BufferedReader(new InputStreamReader(Files.newInputStream(clusterFile)));
		
		String line;
		long count = 1;
		while ((line = clusterLines.readLine()) != null)
		{
			int split = line.indexOf("\t");
			String sPaperHexID = line.substring(0,  split);
			String sCluster = line.substring(split+1);
			Long paperid = Long.parseLong(sPaperHexID, 16);
			clusters.put(paperid, sCluster);
			count++;
			//System.out.println("Java debugging is great " + count);
		}
	}
	
	static void indexMetadata(IndexWriter writer, Path metadataFile, String dataSetName) throws IOException
	{
		BufferedReader metadataLines = new BufferedReader(new InputStreamReader(Files.newInputStream(metadataFile), StandardCharsets.UTF_8));
		
		String line;
		while ((line = metadataLines.readLine()) != null)
		{
			int t1 = line.indexOf('\t');
			int t2 = line.indexOf('\t', t1 + 1);
			
			String paperHexID = line.substring(0, t1);
			Long paperID = Long.parseLong(paperHexID, 16);
			String title = line.substring(t1 + 1, t2);
			Vector<Long> citations = graph.get(paperID);
			Double PRcomponent = pageRanksComponent.get(paperID);
			Long pageRank = pageRanks.get(paperID);
			
			Document doc = new Document();
			doc.add(new TextField("title", title, Field.Store.YES));
			doc.add(new TextField("conference", dataSetName, Field.Store.YES));
			if (pageRank != null)
			{
				doc.add(new NumericDocValuesField("pageRank", pageRank)); //unsure..
				doc.add(new StoredField("pageRank", pageRank));
				doc.add(new NumericDocValuesField("pageRankComponent", Double.doubleToRawLongBits(PRcomponent)));
				doc.add(new StoredField("pageRankComponent", PRcomponent));
			}
			else
			{
				System.out.println("PaperID: " + paperID + " title: " + title + " has no graph information");
			}
			doc.add(new StoredField("paperID", paperID));

			if (citations != null)
			{
				StringBuffer c = new StringBuffer();
				for (int i = 0; i < citations.size(); i++)
				{
					c.append(citations.get(i));
					if (i != citations.size() -1)
					{
						c.append(";");
					}
				}
				
				doc.add(new TextField("citations", c.toString(), Field.Store.YES));
			}
			
			String cluster = clusters.get(paperID);
			if (cluster != null)
			{
				doc.add(new StringField("cluster", cluster, Field.Store.YES));
			}
			
			writer.addDocument(doc);
			
			
		}
	}

	/** Indexes a single document */
	static void indexDoc(IndexWriter writer, Path file) throws IOException {
		InputStream stream = Files.newInputStream(file);
		BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
		String title = br.readLine();
		Document doc = new Document();
		doc.add(new StringField("path", file.toString(), Field.Store.YES));
		doc.add(new TextField("contents", br));
		doc.add(new StringField("title", title, Field.Store.YES));
		writer.addDocument(doc);
		counter++;
		if (counter % 1000 == 0)
			System.out.println("indexing " + counter + "-th file " + file.getFileName());
		;
	}
}