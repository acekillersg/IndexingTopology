package indexingTopology.bolt;

import indexingTopology.data.DataTuple;
import indexingTopology.config.TopologyConfig;
import org.apache.storm.metric.internal.RateTracker;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import indexingTopology.data.DataSchema;
import indexingTopology.streams.Streams;
import indexingTopology.util.*;
import javafx.util.Pair;
import org.apache.storm.tuple.Values;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Created by acelzj on 11/15/16.
 */
public class IngestionBolt extends BaseRichBolt implements Observer {
    private final DataSchema schema;

    private OutputCollector collector;

    private IndexerBuilder indexerBuilder;

    private Indexer indexer;

    private ArrayBlockingQueue<DataTuple> inputQueue;

    private ArrayBlockingQueue<SubQuery> queryPendingQueue;

    private Observable observable;

    private int numTuples;

    private long start;

    private RateTracker rateTracker;

    public IngestionBolt(DataSchema schema) {
        this.schema = schema;
    }
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        collector = outputCollector;

        this.inputQueue = new ArrayBlockingQueue<>(1024);

        this.queryPendingQueue = new ArrayBlockingQueue<>(1024);

        indexerBuilder = new IndexerBuilder();

        indexer = indexerBuilder
                .setTaskId(topologyContext.getThisTaskId())
                .setDataSchema(schema)
                .setInputQueue(inputQueue)
                .setQueryPendingQueue(queryPendingQueue)
                .getIndexer();

        this.observable = indexer;
        observable.addObserver(this);
        start = System.currentTimeMillis();
        numTuples = 0;

        rateTracker = new RateTracker(5 * 1000, 5);
    }

    @Override
    public void cleanup() {
        super.cleanup();
    }

    public void execute(Tuple tuple) {
        if (tuple.getSourceStreamId().equals(Streams.IndexStream)) {
//            DataTuple dataTuple = (DataTuple) tuple.getValueByField("tuple");
            byte[] dataTupleBytes = (byte[]) tuple.getValueByField("tuple");
            DataTuple dataTuple = schema.deserializeToDataTuple(dataTupleBytes);

            Long tupleId = tuple.getLongByField("tupleId");

            int taskId = tuple.getIntegerByField("taskId");

            rateTracker.notify(1);

            try {
                inputQueue.put(dataTuple);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
//            if (numTuples == 0) {
//                start = System.currentTimeMillis();
//            }
//                ++numTuples;
//                if (numTuples >= 1200000) {
//                    System.out.println("Throughput: " + (numTuples * 1000 / ((System.currentTimeMillis() - start)*1.0)));
//                    numTuples = 0;
//                    start = System.currentTimeMillis();
//                }
//                collector.ack(tuple);
//                System.out.println("tuple id " + tupleId);
                if (tupleId % TopologyConfig.EMIT_NUM == 0) {
                    collector.emitDirect(taskId, Streams.AckStream, new Values(tupleId));
                }
            }
        } else if (tuple.getSourceStreamId().equals(Streams.BPlusTreeQueryStream)){
            SubQuery subQuery = (SubQuery) tuple.getValueByField("subquery");
            try {
                queryPendingQueue.put(subQuery);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else if (tuple.getSourceStreamId().equals(Streams.TreeCleanStream)) {
            TimeDomain timeDomain = (TimeDomain) tuple.getValueByField("timeDomain");
            KeyDomain keyDomain = (KeyDomain) tuple.getValueByField("keyDomain");
            indexer.cleanTree(new Domain(keyDomain, timeDomain));
        } else if (tuple.getSourceStreamId().equals(Streams.ThroughputRequestStream)) {
            collector.emit(Streams.ThroughputReportStream, new Values(rateTracker.reportRate()));
        }
    }

    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(Streams.FileInformationUpdateStream,
                new Fields("fileName", "keyDomain", "timeDomain"));

        outputFieldsDeclarer.declareStream(Streams.BPlusTreeQueryStream,
                new Fields("queryId", "serializedTuples"));

        outputFieldsDeclarer.declareStream(Streams.TimestampUpdateStream,
                new Fields("timeDomain", "keyDomain"));

        outputFieldsDeclarer.declareStream(Streams.AckStream, new Fields("tupleId"));

        outputFieldsDeclarer.declareStream(Streams.ThroughputReportStream, new Fields("throughput"));
    }

    @Override
    public void update(Observable o, Object arg) {
        if (o instanceof Indexer) {
            String s = (String) arg;
            if (s.equals("information update")) {
                Pair domainInformation = ((Indexer) o).getDomainInformation();
                String fileName = (String) domainInformation.getKey();
                Domain domain = (Domain) domainInformation.getValue();
                KeyDomain keyDomain = new KeyDomain(domain.getLowerBound(), domain.getUpperBound());
                TimeDomain timeDomain = new TimeDomain(domain.getStartTimestamp(), domain.getEndTimestamp());

                collector.emit(Streams.FileInformationUpdateStream, new Values(fileName, keyDomain, timeDomain));

                collector.emit(Streams.TimestampUpdateStream, new Values(timeDomain, keyDomain));

            } else if (s.equals("query result")) {
                Pair pair = ((Indexer) o).getQueryResult();
                SubQuery subQuery = (SubQuery) pair.getKey();
                List<byte[]> queryResults = (List<byte[]>) pair.getValue();
//                List<byte[]> serializedTuples = new ArrayList<>();
//                for(DataTuple dataTuple: queryResults) {
//                    serializedTuples.add(schema.serializeTuple(dataTuple));
//                }
                collector.emit(Streams.BPlusTreeQueryStream, new Values(subQuery, queryResults));
            }
        }
    }
}
