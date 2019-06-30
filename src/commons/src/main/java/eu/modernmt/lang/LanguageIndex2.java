package eu.modernmt.lang;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LanguageIndex2 {

    public static class Builder {

        private final Map<SimpleLanguageDirection, List<LanguageDirection>> index = new HashMap<>();
        private final Map<String, List<LanguageRule>> rules = new HashMap<>();

        public Builder add(LanguageDirection direction) {
            SimpleLanguageDirection key = SimpleLanguageDirection.fromLanguageDirection(direction);
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(direction);

            return this;
        }

        public Builder addRule(Language2 pattern, Language2 output) throws IllegalArgumentException {
            rules.computeIfAbsent(pattern.getLanguage(), k -> new ArrayList<>())
                    .add(new LanguageRule(pattern, output));

            return this;
        }

        public LanguageIndex2 build() {
            return new LanguageIndex2(index, rules);
        }

    }

    private final Map<SimpleLanguageDirection, List<LanguageDirection>> index;
    private final Map<String, List<LanguageRule>> rules;

    private final Set<LanguageDirection> languages;
    private final ConcurrentHashMap<LanguageDirection, LanguageDirection> mappingCache;

    private LanguageIndex2(Map<SimpleLanguageDirection, List<LanguageDirection>> index, Map<String, List<LanguageRule>> rules) {
        HashSet<LanguageDirection> languages = new HashSet<>();
        for (List<LanguageDirection> entries : index.values())
            languages.addAll(entries);

        this.languages = Collections.unmodifiableSet(languages);
        this.index = index;
        this.rules = rules;
        this.mappingCache = new ConcurrentHashMap<>();
    }

    public Set<LanguageDirection> getLanguages() {
        return languages;
    }

    public int size() {
        return languages.size();
    }

    public LanguageDirection asSingleLanguagePair() {
        return languages.size() == 1 ? languages.iterator().next() : null;
    }

    /**
     * Map the input language pair to one that is compatible with the supported ones,
     * trying to adapt language and region if necessary.
     * It does not try to map the reversed language pair, if needed call mapIgnoringDirection()
     *
     * @param pair the pair to search for
     * @return the supported language pair that matches the input pair, or null if no mapping found
     */
    public LanguageDirection map(LanguageDirection pair) {
        return mappingCache.computeIfAbsent(pair, this::search);
    }

    public LanguageDirection mapIgnoringDirection(LanguageDirection pair) {
        LanguageDirection cached = mappingCache.get(pair);
        if (cached != null)
            return cached;
        cached = mappingCache.get(pair.reversed());
        if (cached != null)
            return cached.reversed();

        LanguageDirection mapped = map(pair);

        if (mapped == null) {
            mapped = map(pair.reversed());
            if (mapped != null)
                mapped = mapped.reversed();
        }

        return mapped;
    }

    private LanguageDirection search(LanguageDirection language) {
        SimpleLanguageDirection key = SimpleLanguageDirection.fromLanguageDirection(language);
        List<LanguageDirection> entries = index.get(key);

        if (entries == null)
            return null;

        // First try if there is a matching pair without rules transformation
        for (LanguageDirection entry : entries) {
            if (entry.isEqualOrMoreGenericThan(language))
                return entry;
        }

        // If not found, try applying transformation
        language = transform(language);

        if (language == null)  // no transformation applied
            return null;

        for (LanguageDirection entry : entries) {
            if (entry.isEqualOrMoreGenericThan(language))
                return entry;
        }

        return null;
    }

    private LanguageDirection transform(LanguageDirection language) {
        Language2 source = transform(language.source);
        Language2 target = transform(language.target);

        if (source == null && target == null)
            return null;

        if (source == null)
            source = language.source;
        if (target == null)
            target = language.target;

        return new LanguageDirection(source, target);
    }

    private Language2 transform(Language2 language) {
        List<LanguageRule> rules = this.rules.get(language.getLanguage());

        if (rules != null) {
            for (LanguageRule rule : rules) {
                if (rule.match(language))
                    return rule.getOutputLanguage();
            }
        }

        // Default behaviour is to transform language in its simplest version, with 'language' code only
        // (returning null signals the caller that the object has been returned untouched)
        return language.getLanguage().equals(language.toLanguageTag()) ? null : new Language2(language.getLanguage());
    }

    @Override
    public String toString() {
        return "i" + languages;
    }

    private static final class SimpleLanguageDirection {

        private final String source;
        private final String target;

        private static SimpleLanguageDirection fromLanguageDirection(LanguageDirection language) {
            return new SimpleLanguageDirection(language.source.getLanguage(), language.target.getLanguage());
        }

        private SimpleLanguageDirection(String source, String target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SimpleLanguageDirection that = (SimpleLanguageDirection) o;

            if (!source.equals(that.source)) return false;
            return target.equals(that.target);
        }

        @Override
        public int hashCode() {
            int result = source.hashCode();
            result = 31 * result + target.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return source + " > " + target;
        }
    }

}