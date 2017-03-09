package indexingTopology.bolt;

import indexingTopology.client.*;
import indexingTopology.data.DataTuple;
import indexingTopology.data.PartialQueryResult;
import indexingTopology.util.Query;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by acelzj on 11/15/16.
 */
public class QueryCoordinatorWithQueryReceiverServer<DataType extends Number> extends QueryCoordinator<DataType> {

    private final int port;

    AtomicLong queryId;

    Server server;

    Map<Long, LinkedBlockingQueue<PartialQueryResult>> queryIdToPartialQueryResults;

//    Map<Long, Semaphore> queryIdToPartialQueryResultSemphore;

    private static final Logger LOG = LoggerFactory.getLogger(QueryCoordinatorWithQueryReceiverServer.class);

    public QueryCoordinatorWithQueryReceiverServer(DataType lowerBound, DataType upperBound, int port) {
        super(lowerBound, upperBound);
        this.port = port;
    }

    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        super.prepare(map, topologyContext, outputCollector);
        queryId = new AtomicLong(0);
        queryIdToPartialQueryResults = new HashMap<>();


        server = new Server(port, QueryServerHandle.class, new Class[]{LinkedBlockingQueue.class, AtomicLong.class, Map.class}, pendingQueue, queryId, queryIdToPartialQueryResults);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server.startDaemon();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
//        queryIdToPartialQueryResultSemphore = new HashMap<>();
    }

    @Override
    public void handlePartialQueryResult(Long queryId, PartialQueryResult partialQueryResult) {
        LinkedBlockingQueue<PartialQueryResult> results = queryIdToPartialQueryResults.computeIfAbsent(queryId, k -> new LinkedBlockingQueue<>());

        try {
            results.put(partialQueryResult);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

//        Semaphore semaphore = queryIdToPartialQueryResultSemphore.computeIfAbsent(queryId, k -> new Semaphore(0));
//        semaphore.release();
    }

    static public class QueryServerHandle extends ServerHandle implements QueryHandle {

        LinkedBlockingQueue<Query> pendingQueryQueue;
        AtomicLong queryIdGenerator;
        Map<Long, LinkedBlockingQueue<PartialQueryResult>> queryresults;
        public QueryServerHandle(LinkedBlockingQueue<Query> pendingQueryQueue, AtomicLong queryIdGenerator, Map<Long, LinkedBlockingQueue<PartialQueryResult>> queryresults) {
            this.pendingQueryQueue = pendingQueryQueue;
            this.queryresults = queryresults;
            this.queryIdGenerator = queryIdGenerator;
        }

        @Override
        public void handle(QueryRequest request) throws IOException {
            try {
                final long queryid = queryIdGenerator.getAndIncrement();

                LinkedBlockingQueue<PartialQueryResult> results = queryresults.computeIfAbsent(queryid, k -> new LinkedBlockingQueue<>());

                LOG.info(String.format("A new Query[%d] ({0}, {1}, {2}, {3}) is added to the pending queue.", queryid),
                        request.low, request.high, request.startTime, request.endTime);
                pendingQueryQueue.put(new Query<>(queryid, request.low, request.high, request.startTime, request.endTime));
//                DataTuple dataTuple = new DataTuple();
//                dataTuple.add("ID 1");
//                dataTuple.add(100);
//                dataTuple.add(3.14);
//
//                DataTuple dataTuple1 = new DataTuple();
//                dataTuple1.add("ID 2");
//                dataTuple1.add(200);
//                dataTuple1.add(6.34);
//
//                PartialQueryResult particalQueryResult = new PartialQueryResult();
//                particalQueryResult.add(dataTuple);
//                particalQueryResult.add(dataTuple1);
                objectOutputStream.writeObject(new QueryResponse(results.take(), queryid));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}