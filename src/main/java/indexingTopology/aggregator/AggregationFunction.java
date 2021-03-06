package indexingTopology.aggregator;

import java.io.Serializable;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Created by robert on 10/3/17.
 */
public interface AggregationFunction<V, A> extends Serializable{
    A aggregateFunction(V value, A originalA);
    A init();
}
