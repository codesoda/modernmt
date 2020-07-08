package eu.modernmt.data;

import java.util.Collection;
import java.util.Map;

/**
 * Created by davide on 31/10/17.
 */
public interface DataBatch {

    Collection<TranslationUnitMessage> getTranslationUnits();

    Collection<DeletionMessage> getDeletions();

    Map<Short, Long> getChannelPositions();

}
