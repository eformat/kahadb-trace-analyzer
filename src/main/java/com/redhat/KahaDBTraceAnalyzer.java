package com.redhat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.StringTokenizer;

/**
 * kahaDB MessageDatabase Trace Analyzer
 *
 */
public class KahaDBTraceAnalyzer
{

    private String[] fullSet = null;
    private String[] currentSet = null;
    private String[] priorSet = null;
    private static String LOG_FILE = "kahadb.log";
    private int count = -1;
    private int containsAcks = 0;
    private static boolean concise = false;
    private boolean checkpointDone = false;

    public static void main( String[] args )
    {
        if(args.length > 0)
        {
            LOG_FILE = args[0];
        }
        String sConcise = System.getProperty("concise", "false");
        //System.out.println("concise: " + sConcise);
        if(sConcise.equalsIgnoreCase("true"))
        {
           concise = true;
        }
        KahaDBTraceAnalyzer analyzer = new KahaDBTraceAnalyzer();
        analyzer.analyze();
    }

    private void analyze()
    {
        //read log file
        try
        {
	        System.out.println("Using log file: " + LOG_FILE);
            URL fileURL = this.getClass().getResource( "/" + LOG_FILE );
            if(fileURL == null)
            {
               System.out.println("Unable to locate log file, please check the name.");
               System.exit(-3);
            }
            BufferedReader br = new BufferedReader(new FileReader(new File(fileURL.toURI())));
            String line;
            int inUse = 0;

            while ((line = br.readLine()) != null)
            {
                if((line.contains("MessageDatabase")) && (line.contains("gc candidates")))
                {
                    //System.out.println(line);
                    // strip the line and get to the important stuff
                    line = line.substring(line.indexOf("gc candidates"), line.length());
                    // beginning of trace output, acquire full journal set
                    if(line.contains("set:"))
                    {
                        // check if we have processed multiple occurrences
                        if(count != -1)
                        {
                            logStats();
                            // reset containsAcks
                            containsAcks = 0;
                            checkpointDone = false;
                        }
                        System.out.println("Acquiring Full Set...");
                        fullSet = acquireSet(line, false);
                        System.out.println("\nFull journal set: " + fullSet.length);
                        count = fullSet.length;
                        priorSet = fullSet;
                    }

                    // only makes sense to gather stats once we have the full set
                    if(fullSet != null) {

                        // check set after first transaction
                        if (line.contains("after first tx:")) {
                            currentSet = acquireSet(line, false);
                            inUse = priorSet.length - currentSet.length;
                            logDestStats("after first tx", inUse);
                            count = count - inUse;
                            priorSet = currentSet;
                        }

                        // check set after producerSequenceIdTrackerLocation
                        if (line.contains("producerSequenceIdTrackerLocation")) {
                            currentSet = acquireSet(line, false);
                            inUse = priorSet.length - currentSet.length;
                            logDestStats("producerSequenceIdTrackerLocation", inUse);
                            count = count - inUse;
                            priorSet = currentSet;
                        }

                        if (line.contains("ackMessageFileMapLocation")) {
                            currentSet = acquireSet(line, false);
                            inUse = priorSet.length - currentSet.length;
                            logDestStats("ackMessageFileMapLocation", inUse);
                            count = count - inUse;
                            priorSet = currentSet;
                        }

                        if (line.contains("tx range")) {
                            currentSet = acquireSet(line, true);
                            inUse = priorSet.length - currentSet.length;
                            logDestStats("tx range", inUse);
                            count = count - inUse;
                            priorSet = currentSet;
                        }

                        if (line.contains("dest")) {
                            currentSet = acquireSet(line, false);
                            inUse = priorSet.length - currentSet.length;
                            logDestStats(acquireDest(line), inUse);
                            count = count - inUse;
                            priorSet = currentSet;
                        }
                    }
                }

                // once we have the full working set, track acks and the final checkpoint
                if(fullSet != null) {
                    if ((line.contains("MessageDatabase")) && (line.contains("not removing data file"))) {
                        containsAcks++;
                    }

                    if ((line.contains("MessageDatabase")) && (line.contains("Checkpoint done."))) {
                        // once we know we have a full set, then we can look for the checkpoint done message
                        //if (fullSet != null) {
                            //System.out.println("Checkpoint complete!");
                            checkpointDone = true;
                        //}
                    }
                }
            }
            br.close();

            logStats();
        }
        catch(IOException ioe)
        {
            System.out.println("Error: " + ioe);
            System.exit(-1);
        }
        catch(URISyntaxException urise)
        {
            System.out.println("Error: " + urise);
            System.exit(-2);
        }
    }

    private String[] acquireSet(String line, boolean tx)
    {
        String[] set = null;
        ArrayList<String> setList = new ArrayList();

        if(tx)
        {
            line = line.substring(line.indexOf("]") + 2, line.length());
            line = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
        }
        else
        {
            line = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
        }

        StringTokenizer token = new StringTokenizer(line, ",");

        while (token.hasMoreElements())
        {
            setList.add((String)token.nextElement());
        }

        set = new String[setList.size()];
        return  set;
    }

    private String acquireDest(String line)
    {
        String type = line.substring(line.indexOf("dest:") + 5, line.indexOf("dest:") + 6);
        //System.out.println("Type: " + type);
        line = line.substring(line.indexOf("dest:") + 7, line.length());
        return line.substring(0, line.indexOf(",")) + (type.equals("0") ? " (Queue)" : " (Topic)");
    }

    private void logDestStats(String dest, int count)
    {
        if(concise && count == 0)
        {
           // do nothing and return
           return;
        }
        System.out.println(count + " --- " + dest);
    }

    private void logStats()
    {
        if(fullSet != null) {
            if (containsAcks > 0) {
                System.out.println("Journals containing acks: " + containsAcks);
            }
            System.out.println("Candidates for cleanup: " + (count - containsAcks) + "\n");
            System.out.println((checkpointDone ? "Analysis is complete\n" : "!!! Unable to determine if checkpoint is done. Please try increasing log size !!!\n"));
        }
        else
        {
            System.out.println("\nUnable to determine full log set\nCheck that TRACE level logging has been enabled for org.apache.activemq.store.kahadb.MessageDatabase"
            + "\nand that the log contains a full output from the trace logging.  In some cases you may need to increase the log size.\n");
        }
    }
}