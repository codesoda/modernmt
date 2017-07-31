package eu.modernmt.context.lucene.storage;

import eu.modernmt.lang.LanguagePair;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Created by davide on 22/09/16.
 */
public class CorporaIndex implements Closeable {

    public static CorporaIndex load(Options.AnalysisOptions analysisOptions, File indexFile, File bucketsFolder) throws IOException {
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(indexFile));

            // Reading channels

            int length = Integer.parseInt(reader.readLine());
            HashMap<Short, Long> channels = new HashMap<>(length);

            for (int i = 0; i < length; i++) {
                String[] parts = reader.readLine().split(":");
                channels.put(Short.parseShort(parts[0]), Long.parseLong(parts[1]));
            }

            // Reading buckets

            length = Integer.parseInt(reader.readLine());
            ArrayList<CorpusBucket> buckets = new ArrayList<>(length);

            for (int i = 0; i < length; i++) {
                CorpusBucket bucket = CorpusBucket.deserialize(analysisOptions, bucketsFolder, reader);
                buckets.add(bucket);
            }

            // Creating result

            return new CorporaIndex(indexFile, analysisOptions, bucketsFolder, buckets, channels);
        } catch (RuntimeException e) {
            throw new IOException("Invalid index file at " + indexFile, e);
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    private final File file;
    private final File swapFile;
    private final Options.AnalysisOptions analysisOptions;
    private final File bucketsFolder;
    private final HashMap<BucketKey, CorpusBucket> bucketByKey;
    private final HashMap<Long, HashSet<CorpusBucket>> bucketsByDomain;
    private final HashMap<Short, Long> channels;

    public CorporaIndex(File file, Options.AnalysisOptions analysisOptions, File bucketsFolder) {
        this(file, analysisOptions, bucketsFolder, Collections.emptyList(), new HashMap<>());
    }

    private CorporaIndex(File file, Options.AnalysisOptions analysisOptions, File bucketsFolder, Collection<CorpusBucket> buckets, HashMap<Short, Long> channels) {
        this.file = file;
        this.swapFile = new File(file.getParentFile(), "~" + file.getName());
        this.analysisOptions = analysisOptions;
        this.bucketsFolder = bucketsFolder;
        this.channels = channels;

        this.bucketByKey = new HashMap<>(buckets.size());
        this.bucketsByDomain = new HashMap<>(buckets.size());

        for (CorpusBucket bucket : buckets) {
            BucketKey key = BucketKey.forBucket(bucket);
            this.bucketByKey.put(key, bucket);
            this.bucketsByDomain.computeIfAbsent(bucket.getDomain(), k -> new HashSet<>()).add(bucket);
        }

    }

    public boolean registerData(short channel, long position) {
        Long existent = this.channels.get(channel);

        if (existent == null || position > existent) {
            this.channels.put(channel, position);
            return true;
        } else {
            return false;
        }
    }

    public CorpusBucket getBucket(LanguagePair direction, long domain) {
        BucketKey key = new BucketKey(direction, domain);

        CorpusBucket bucket = bucketByKey.get(key);

        if (bucket == null) {
            bucket = new CorpusBucket(analysisOptions, bucketsFolder, direction, domain);

            this.bucketByKey.put(key, bucket);
            this.bucketsByDomain.computeIfAbsent(domain, k -> new HashSet<>()).add(bucket);
        }

        return bucket;
    }

    public Collection<CorpusBucket> getBucketsByDomain(long domain) {
        return this.bucketsByDomain.get(domain);
    }

    public void remove(CorpusBucket bucket) {
        Long domain = bucket.getDomain();
        BucketKey key = BucketKey.forBucket(bucket);

        bucketByKey.remove(key);
        HashSet<CorpusBucket> buckets = bucketsByDomain.get(domain);

        if (buckets != null) {
            buckets.remove(bucket);

            if (buckets.isEmpty())
                bucketsByDomain.remove(domain);
        }
    }

    public Collection<CorpusBucket> getBuckets() {
        return bucketByKey.values();
    }

    public synchronized HashMap<Short, Long> getChannels() {
        return new HashMap<>(channels);
    }

    public void save() throws IOException {
        this.store(this.swapFile);
        Files.move(this.swapFile.toPath(), this.file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE);
        FileUtils.deleteQuietly(this.swapFile);
    }

    private void store(File path) throws IOException {
        Writer writer = null;

        try {
            writer = new BufferedWriter(new FileWriter(path, false));

            // Writing channels

            writer.append(Integer.toString(channels.size()));
            writer.append('\n');

            for (Map.Entry<Short, Long> channel : channels.entrySet()) {
                writer.append(Short.toString(channel.getKey()));
                writer.append(':');
                writer.append(Long.toString(channel.getValue()));
                writer.append('\n');
            }

            // Writing buckets

            writer.append(Integer.toString(bucketByKey.size()));
            writer.append('\n');

            for (CorpusBucket bucket : bucketByKey.values())
                CorpusBucket.serialize(bucket, writer);
        } finally {
            IOUtils.closeQuietly(writer);
        }
    }

    @Override
    public void close() throws IOException {
        bucketByKey.values().forEach(IOUtils::closeQuietly);
    }

    private static final class BucketKey {

        private final LanguagePair direction;
        private final long domain;

        public static BucketKey forBucket(CorpusBucket bucket) {
            return new BucketKey(bucket.getLanguageDirection(), bucket.getDomain());
        }

        public BucketKey(LanguagePair direction, long domain) {
            this.direction = direction;
            this.domain = domain;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            BucketKey bucketKey = (BucketKey) o;

            if (domain != bucketKey.domain) return false;
            return direction.equals(bucketKey.direction);
        }

        @Override
        public int hashCode() {
            int result = direction.hashCode();
            result = 31 * result + (int) (domain ^ (domain >>> 32));
            return result;
        }
    }
}
