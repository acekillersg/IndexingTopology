package indexingTopology.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import indexingTopology.config.TopologyConfig;
import indexingTopology.exception.UnsupportedGenericException;
import indexingTopology.filesystem.FileSystemHandler;
import indexingTopology.filesystem.HdfsFileSystemHandler;
import indexingTopology.filesystem.LocalFileSystemHandler;
import indexingTopology.util.texi.Car;
import indexingTopology.util.texi.City;
import indexingTopology.util.texi.TrajectoryGenerator;
import indexingTopology.util.texi.TrajectoryUniformGenerator;
import org.apache.storm.tuple.Values;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * Created by acelzj on 1/4/17.
 */
public class KryoTemplateSerializerTest {

    List<String> fieldNames = new ArrayList<String>(Arrays.asList("id", "zcode", "payload"));
    List<Class> valueTypes = new ArrayList<Class>(Arrays.asList(Double.class, Double.class, String.class));

    @Test
    public void testDeserialize() throws IOException, UnsupportedGenericException {

        final int payloadSize = 10;

        final double x1 = 0;
        final double x2 = 1000;
        final double y1 = 0;
        final double y2 = 500;
        final int partitions = 100;

        TrajectoryGenerator generator = new TrajectoryUniformGenerator(10000, x1, x2, y1, y2);
        City city = new City(x1, x2, y1, y2, partitions);

        int numTuples = TopologyConfig.NUMBER_TUPLES_OF_A_CHUNK;
        Long timestamp = 0L;

        int chunkId = 0;

        List<String> fileNames = new ArrayList<>();

        BTree indexedData = new BTree(TopologyConfig.BTREE_ORDER);

        int numberOfTuples = 60;

        Random random = new Random();

        List<Integer> keys = new ArrayList<>();

        for (int i = 0; i < numTuples; ++i) {
            List<Object> values = new ArrayList<>();
            Car car = generator.generate();
            values.add((double) car.id);
            values.add((double) city.getZCodeForALocation(car.x, car.y));
            values.add(new String(new char[payloadSize]));
            values.add(timestamp);
            byte[] bytes = null;
            bytes = serializeIndexValue(values);
            indexedData.insert((double) car.id, bytes);
        }


        indexedData.serializeLeaves();

        Output output = new Output(500000);

        Kryo kryo = new Kryo();
        kryo.register(BTree.class, new KryoTemplateSerializer());

        kryo.writeObject(output, indexedData);

        byte[] bytes = output.toBytes();

        Input input = new Input(bytes);

        indexedData = kryo.readObject(input, BTree.class);

    }

    @Test
    public void testTemplateAndLeaveDeserialization() throws IOException, UnsupportedGenericException {


//        System.out.println("Total " + Runtime.getRuntime().totalMemory());

        final int payloadSize = 10;

        final double x1 = 0;
        final double x2 = 1000;
        final double y1 = 0;
        final double y2 = 500;
        final int partitions = 100;

        TrajectoryGenerator generator = new TrajectoryUniformGenerator(10000, x1, x2, y1, y2);
        City city = new City(x1, x2, y1, y2, partitions);

        int numTuples = 1200000;
        Long timestamp = 0L;

        int chunkId = 0;

        List<String> fileNames = new ArrayList<>();

//        BTree indexedData = new BTree(TopologyConfig.BTREE_ORDER);

        int numberOfTuples = 120;

        Random random = new Random();

        List<Integer> keys = new ArrayList<>();


//        for (int j = 0; j < 10; ++j) {
            BTree indexedData = new BTree(TopologyConfig.BTREE_ORDER);

            for (int i = 0; i < numTuples; ++i) {
                List<Object> values = new ArrayList<>();
                Car car = generator.generate();
                values.add((double) car.id);
//                values.add((double) city.getZCodeForALocation(car.x, car.y));
                values.add((double) i);
                values.add(new String(new char[payloadSize]));
                values.add(timestamp);
                byte[] bytes = null;
                bytes = serializeIndexValue(values);
//                indexedData.insert((double) city.getZCodeForALocation(car.x, car.y), bytes);
                indexedData.insert(i, bytes);
            }

//            System.out.println("Used : " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));

//            indexedData.printBtree();

            byte[] leavesInBytes = indexedData.serializeLeaves();

            Output output = new Output(5000000, 20000000);

            Kryo kryo = new Kryo();
            kryo.register(BTree.class, new KryoTemplateSerializer());
            kryo.register(BTreeLeafNode.class, new KryoLeafNodeSerializer());

            kryo.writeObject(output, indexedData);

            byte[] bytes = output.toBytes();

            int lengthOfTemplate = bytes.length;

//            System.out.println(lengthOfTemplate);

            output = new Output(4);

            output.writeInt(lengthOfTemplate);

            byte[] lengthInBytes = output.toBytes();

            MemChunk chunk = MemChunk.createNew(4 + lengthOfTemplate + leavesInBytes.length);

            chunk.write(lengthInBytes);

            chunk.write(bytes);

            chunk.write(leavesInBytes);

            FileSystemHandler fileSystemHandler = null;

            String fileName = null;

//            System.out.println("Before read " + Runtime.getRuntime().freeMemory());

            try {
                if (TopologyConfig.HDFSFlag) {
                    fileSystemHandler = new HdfsFileSystemHandler(TopologyConfig.dataDir);
                } else {
                    fileSystemHandler = new LocalFileSystemHandler(TopologyConfig.dataDir);
                }

                fileName = "taskId" + 0 + "chunk" + 0;
                fileSystemHandler.writeToFileSystem(chunk, "/", fileName);
            } catch (IOException e) {
                e.printStackTrace();
            }

            byte[] temlateLengthInBytes = new byte[4];

            long fileOpenTime = 0;

            long totalStart = System.currentTimeMillis();

            long start = System.currentTimeMillis();
            fileSystemHandler.openFile("/", "taskId0chunk0");
            fileOpenTime += System.currentTimeMillis() - start;

            int fileLength = 64902601;
            byte[] bytesToRead = new byte[fileLength];
//            long wholeStart = System.currentTimeMillis();
//            fileSystemHandler.readBytesFromFile(0, bytesToRead);
//            System.out.println("time " + (System.currentTimeMillis() - wholeStart));

            long templateReadStart = System.currentTimeMillis();
            fileSystemHandler.readBytesFromFile(0, temlateLengthInBytes);


            Input input = new Input(temlateLengthInBytes);

            int length = input.readInt();

            byte[] templateInBytes = new byte[length];

//            System.out.println("length " + length);
            fileSystemHandler.readBytesFromFile(4, templateInBytes);

            input = new Input(templateInBytes);

            indexedData = kryo.readObject(input, BTree.class);

//            System.out.println("template read time " + (System.currentTimeMillis() - templateReadStart));

//            indexedData.printBtree();

//            BTreeNode mostLeftNode = indexedData.findInnerNodeShouldContainKey(0.0);
//            BTreeNode mostRightNode = indexedData.findInnerNodeShouldContainKey(120.0);

            List<Integer> offsets = indexedData.getOffsetsOfLeafNodesShouldContainKeys(0
                    , 1);

            List<byte[]> list = new ArrayList<>();


            long leaveReadTime = 0;

            long getTupleTime = 0;

            System.out.println(offsets.size());

            int startOffset = offsets.get(0);

            long leafReadStart = System.currentTimeMillis();

            bytesToRead = new byte[4];
            int lastOffset = offsets.get(offsets.size() - 1);
            fileSystemHandler.readBytesFromFile(lastOffset + length + 4, bytesToRead);

            Input input1 = new Input(bytesToRead);
            int tempLength = input1.readInt();
            int totalLength = tempLength + (lastOffset - offsets.get(0));


            List<BTreeLeafNode> leaves = new ArrayList<>();

            bytesToRead = new byte[totalLength + 4];

            fileSystemHandler.readBytesFromFile(startOffset + length + 4, bytesToRead);


            Input input2 = new Input(bytesToRead);

            System.out.println(offsets);

            for (Integer offset : offsets) {
                input2.setPosition(input2.position() + 4);

                BTreeLeafNode leafNode = kryo.readObject(input2, BTreeLeafNode.class);

                long tuplgGetStart = System.currentTimeMillis();

                list.addAll(leafNode.getTuplesWithinKeyRange(0, 1));

                getTupleTime += (System.currentTimeMillis() - tuplgGetStart);
            }



//            for (Integer offset : offsets) {
//
//
//                fileSystemHandler.seek(offset + length + 4);
//
//                byte[] lengthInByte = new byte[4];
//
//                fileSystemHandler.readBytesFromFile(offset + length + 4, lengthInByte);
//
//                int lengthOfLeaveInBytes = ByteBuffer.wrap(lengthInByte, 0, 4).getInt();
//
//                System.out.println(lengthOfLeaveInBytes);
//
//                byte[] leafInByte = new byte[lengthOfLeaveInBytes];

//                fileSystemHandler.seek(offset + length + 4 + 4);
//                long leafNodestart = System.currentTimeMillis();
//                start = System.currentTimeMillis();
//                fileSystemHandler.readBytesFromFile(offset + length + 4 + 4, leafInByte);
//                System.out.println("leaf node read time " + (System.currentTimeMillis() - leafNodestart));
//                leaveReadTime += (System.currentTimeMillis() - start);
//
//                input = new Input(leafInByte);

//                BTreeLeafNode leaf = kryo.readObject(input, BTreeLeafNode.class);

//                leaf.print();



//                start = System.currentTimeMillis();
//                list.addAll(leaf.getTuplesWithinKeyRange(0.0, 1.0));

//            }

            start = System.currentTimeMillis();
            fileSystemHandler.closeFile();
            fileOpenTime += (System.currentTimeMillis() - start);

//            System.out.println("total " + (System.currentTimeMillis() - totalStart));


//            System.out.println("file open time " + fileOpenTime);
//            System.out.println("get tuple time " + getTupleTime);
//            System.out.println("leaf read time " + leaveReadTime);
//
//            System.out.println("after read " + Runtime.getRuntime().freeMemory());

            assertEquals(2, list.size());
//        }
    }

    /*
    @Test
    public void testTemplateUpdateDeserialization() throws UnsupportedGenericException, IOException {
        final int payloadSize = 10;

        final double x1 = 0;
        final double x2 = 1000;
        final double y1 = 0;
        final double y2 = 500;
        final int partitions = 100;

        TrajectoryGenerator generator = new TrajectoryUniformGenerator(10000, x1, x2, y1, y2);
        City city = new City(x1, x2, y1, y2, partitions);

        int numTuples = 1024;
        Long timestamp = 0L;

        int chunkId = 0;

        List<String> fileNames = new ArrayList<>();

        BTree indexedData = new BTree(TopologyConfig.BTREE_ORDER);

        Random random = new Random();

        while (chunkId <= 1) {
            for (int i = 0; i < numTuples; ++i) {
                List<Object> values = new ArrayList<>();
                Car car = generator.generate();
                values.add((double) car.id);
                Double key = random.nextDouble();
                values.add(key);
                values.add(new String(new char[payloadSize]));
                values.add(timestamp);
                byte[] bytes = null;
                bytes = serializeIndexValue(values);
                indexedData.insert(key, bytes);
            }

            byte[] leavesInBytes = indexedData.serializeLeaves();

            Output output = new Output(5000000, 20000000);

            Kryo kryo = new Kryo();
            kryo.register(BTree.class, new KryoTemplateSerializer());
            kryo.register(BTreeLeafNode.class, new KryoLeafNodeSerializer());

            kryo.writeObject(output, indexedData);

            byte[] bytes = output.toBytes();

            int lengthOfTemplate = bytes.length;

            output = new Output(4);

            output.writeInt(lengthOfTemplate);

            byte[] lengthInBytes = output.toBytes();

            MemChunk chunk = MemChunk.createNew(4 + lengthOfTemplate + leavesInBytes.length);

            chunk.write(lengthInBytes);

            chunk.write(bytes);

            chunk.write(leavesInBytes);

            FileSystemHandler fileSystemHandler = null;


            try {
                String fileName = null;
                if (TopologyConfig.HDFSFlag) {
                    fileSystemHandler = new HdfsFileSystemHandler(TopologyConfig.dataDir);
                } else {
                    fileSystemHandler = new LocalFileSystemHandler(TopologyConfig.dataDir);
                }

                fileName = "taskId" + 0 + "chunk" + chunkId;
                fileNames.add(fileName);
                fileSystemHandler.writeToFileSystem(chunk, "/", fileName);
            } catch (IOException e) {
                e.printStackTrace();
            }

            indexedData = indexedData.clone();

//            indexedData.clearPayload();

//            indexedData.printBtree();

            for (String fileName : fileNames) {

                byte[] templateLengthInBytes = new byte[4];

                fileSystemHandler.openFile("/", fileName);

                fileSystemHandler.readBytesFromFile(templateLengthInBytes);

                Input input = new Input(templateLengthInBytes);

                int length = input.readInt();

                byte[] templateInBytes = new byte[length];

                fileSystemHandler.readBytesFromFile(4, templateInBytes);

                input = new Input(templateInBytes);

                BTree bTree = kryo.readObject(input, BTree.class);

//                BTreeNode mostLeftNode = bTree.findInnerNodeShouldContainKey(994.0);
//                BTreeNode mostRightNode = bTree.findInnerNodeShouldContainKey(1000.0);

                List<Integer> offsets = bTree.getOffsetsOfLeafNodesShouldContainKeys(0.0, 1.0
                        );

//                System.out.println(offsets);

                for (Integer offset : offsets) {

                    fileSystemHandler.seek(offset + length + 4);

                    byte[] lengthInByte = new byte[4];

                    fileSystemHandler.readBytesFromFile(offset + length + 4, lengthInByte);

                    int lengthOfLeaveInBytes = ByteBuffer.wrap(lengthInByte, 0, 4).getInt();

                    byte[] leafInByte = new byte[lengthOfLeaveInBytes + 1];

                    fileSystemHandler.seek(offset + length + 4 + 4);

                    fileSystemHandler.readBytesFromFile(offset + length + 4 + 4, leafInByte);

                    input = new Input(leafInByte);

                    BTreeLeafNode leaf = kryo.readObject(input, BTreeLeafNode.class);

                    ArrayList<byte[]> tuples = leaf.getTuplesWithinKeyRange(0.0, 1.0);
//                ArrayList<byte[]> tuples = leaf.searchAndGetTuples(994.0);

//                    for (int j = 0; j < tuples.size(); ++j) {
//                        System.out.println(deserialize(tuples.get(j)));
//                    }

                }
                fileSystemHandler.closeFile();
            }

            ++chunkId;
        }

    }
    */

    public byte[] serializeIndexValue(List<Object> values) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        for (int i = 0;i < valueTypes.size(); ++i) {
            if (valueTypes.get(i).equals(Double.class)) {
                byte [] b = ByteBuffer.allocate(Double.SIZE / Byte.SIZE).putDouble((Double) values.get(i)).array();
                bos.write(b);
            } else if (valueTypes.get(i).equals(String.class)) {
                byte [] b = ((String) values.get(i)).getBytes();
                byte [] sizeHeader = ByteBuffer.allocate(Integer.SIZE/ Byte.SIZE).putInt(b.length).array();
                bos.write(sizeHeader);
                bos.write(b);
            } else {
                throw new IOException("Only classes supported till now are string and double");
            }
        }

        byte [] b = ByteBuffer.allocate(Long.SIZE / Byte.SIZE).putLong((Long) values.get(valueTypes.size())).array();
        bos.write(b);
        return bos.toByteArray();
    }

    public Values deserialize(byte [] b) throws IOException {
        Values values = new Values();
        int offset = 0;
        for (int i = 0; i < valueTypes.size(); i++) {
            if (valueTypes.get(i).equals(Double.class)) {
                int len = Double.SIZE/Byte.SIZE;
                double val = ByteBuffer.wrap(b, offset, len).getDouble();
                values.add(val);
                offset += len;
            } else if (valueTypes.get(i).equals(String.class)) {
                int len = Integer.SIZE/Byte.SIZE;
                int sizeHeader = ByteBuffer.wrap(b, offset, len).getInt();
                offset += len;
                len = sizeHeader;
                String val = new String(b, offset, len);
                values.add(val);
                offset += len;
            } else {
                throw new IOException("Only classes supported till now are string and double");
            }
        }

        int len = Long.SIZE / Byte.SIZE;
        Long val = ByteBuffer.wrap(b, offset, len).getLong();
        values.add(val);
        return values;
    }

}