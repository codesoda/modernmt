package eu.modernmt.decoder.opennmt.memory.lucene;

import eu.modernmt.data.TranslationUnit;
import eu.modernmt.decoder.opennmt.memory.ScoreEntry;
import eu.modernmt.io.TokensOutputStream;
import eu.modernmt.lang.LanguagePair;
import eu.modernmt.model.Sentence;
import org.apache.lucene.document.*;
import org.apache.lucene.util.BytesRef;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by davide on 23/05/17.
 */
class DocumentBuilder {

    public static final String CHANNELS_FIELD = "channels";

    public static final String DOMAIN_ID_FIELD = "domain";
    public static final String LANGUAGE_FIELD = "language";
    public static final String SENTENCE_FIELD = "sentence";
    public static final String TRANSLATION_FIELD = "translation";

    public static String serialize(LanguagePair pair) {
        return pair.source.toLanguageTag() + " \u2192 " + pair.target.toLanguageTag();
    }

    public static Document build(TranslationUnit unit) {
        return build(unit.direction, unit.domain, unit.sourceSentence, unit.targetSentence);
    }

    public static Document build(LanguagePair direction, long domain, Sentence sentence, Sentence translation) {
        String s = TokensOutputStream.toString(sentence, false, true);
        String t = TokensOutputStream.toString(translation, false, true);
        return build(direction, domain, s, t);
    }

    public static Document build(LanguagePair direction, long domain, String sentence, String translation) {
        Document document = new Document();
        document.add(new LongField(DOMAIN_ID_FIELD, domain, Field.Store.YES));
        document.add(new StringField(LANGUAGE_FIELD, serialize(direction), Field.Store.NO));
        document.add(new TextField(SENTENCE_FIELD, sentence, Field.Store.YES));
        document.add(new StoredField(TRANSLATION_FIELD, translation));

        return document;
    }

    public static ScoreEntry parseEntry(Document doc) {
        long domain = Long.parseLong(doc.get(DOMAIN_ID_FIELD));
        String[] sentence = doc.get(SENTENCE_FIELD).split(" ");
        String[] translation = doc.get(TRANSLATION_FIELD).split(" ");

        return new ScoreEntry(domain, sentence, translation);
    }

    public static Document build(Map<Short, Long> channels) {
        ByteBuffer buffer = ByteBuffer.allocate(10 * channels.size());
        for (Map.Entry<Short, Long> entry : channels.entrySet()) {
            buffer.putShort(entry.getKey());
            buffer.putLong(entry.getValue());
        }

        Document document = new Document();
        document.add(new LongField(DOMAIN_ID_FIELD, 0, Field.Store.YES));
        document.add(new StoredField(CHANNELS_FIELD, buffer.array()));

        return document;
    }

    public static Map<Short, Long> parseChannels(Document document) {
        HashMap<Short, Long> result = new HashMap<>();

        BytesRef value = document.getBinaryValue(CHANNELS_FIELD);
        ByteBuffer buffer = ByteBuffer.wrap(value.bytes);

        while (buffer.hasRemaining()) {
            short channel = buffer.getShort();
            long position = buffer.getLong();
            result.put(channel, position);
        }

        return result;
    }

}
