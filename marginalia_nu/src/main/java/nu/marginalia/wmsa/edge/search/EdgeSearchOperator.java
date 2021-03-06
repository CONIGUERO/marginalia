package nu.marginalia.wmsa.edge.search;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import nu.marginalia.wmsa.configuration.server.Context;
import nu.marginalia.wmsa.edge.assistant.client.AssistantClient;
import nu.marginalia.wmsa.edge.assistant.dict.WikiArticles;
import nu.marginalia.wmsa.edge.data.dao.EdgeDataStoreDao;
import nu.marginalia.wmsa.edge.index.client.EdgeIndexClient;
import nu.marginalia.wmsa.edge.index.model.IndexBlock;
import nu.marginalia.wmsa.edge.index.service.SearchOrder;
import nu.marginalia.wmsa.edge.model.*;
import nu.marginalia.wmsa.edge.model.search.*;
import nu.marginalia.wmsa.edge.search.query.model.EdgeSearchQuery;
import nu.marginalia.wmsa.edge.search.query.QueryFactory;
import nu.marginalia.wmsa.edge.search.query.model.EdgeUserSearchParameters;
import nu.marginalia.wmsa.edge.search.results.SearchResultValuator;
import nu.marginalia.wmsa.edge.search.results.model.AccumulatedQueryResults;
import nu.marginalia.wmsa.edge.search.results.SearchResultDecorator;
import nu.marginalia.wmsa.edge.search.results.UrlDeduplicator;
import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class EdgeSearchOperator {

    private static final Logger logger = LoggerFactory.getLogger(EdgeSearchOperator.class);
    private final AssistantClient assistantClient;
    private final EdgeDataStoreDao edgeDataStoreDao;
    private final EdgeIndexClient indexClient;
    private final QueryFactory queryFactory;
    private final SearchResultDecorator resultDecorator;
    private final SearchResultValuator valuator;
    private final Comparator<EdgeUrlDetails> resultListComparator;

    @Inject
    public EdgeSearchOperator(AssistantClient assistantClient,
                              EdgeDataStoreDao edgeDataStoreDao,
                              EdgeIndexClient indexClient,
                              QueryFactory queryFactory,
                              SearchResultDecorator resultDecorator,
                              SearchResultValuator valuator
                              ) {

        this.assistantClient = assistantClient;
        this.edgeDataStoreDao = edgeDataStoreDao;
        this.indexClient = indexClient;
        this.queryFactory = queryFactory;
        this.resultDecorator = resultDecorator;
        this.valuator = valuator;

        Comparator<EdgeUrlDetails> c = Comparator.comparing(ud -> Math.round(10*(ud.getTermScore() - ud.rankingIdAdjustment())));
        resultListComparator = c.thenComparing(EdgeUrlDetails::getRanking).thenComparing(EdgeUrlDetails::getId);
    }

    public List<EdgeUrlDetails> doApiSearch(Context ctx,
                                           EdgeUserSearchParameters params) {


        var processedQuery = queryFactory.createQuery(params);

        logger.info("Human terms (API): {}", Strings.join(processedQuery.searchTermsHuman, ','));

        DecoratedSearchResultSet queryResults = performQuery(ctx, processedQuery, true);

        return queryResults.resultSet;
    }

    public DecoratedSearchResults doSearch(Context ctx, EdgeUserSearchParameters params, String evalResult) {


        Observable<WikiArticles> definitions = getWikiArticle(ctx, params.getHumanQuery());

        var processedQuery = queryFactory.createQuery(params);

        logger.info("Human terms: {}", Strings.join(processedQuery.searchTermsHuman, ','));

        DecoratedSearchResultSet queryResults = performQuery(ctx, processedQuery, false);

        return new DecoratedSearchResults(params,
                getProblems(ctx, params.getHumanQuery(), evalResult, queryResults, processedQuery),
                evalResult,
                definitions.onErrorReturn((e) -> new WikiArticles()).blockingFirst(),
                queryResults.resultSet,
                processedQuery.domain,
                getDomainId(processedQuery.domain));
    }

    private int getDomainId(String domain) {
        int domainId = -1;
        try {
            if (domain != null) {
                return edgeDataStoreDao.getDomainId(new EdgeDomain(domain)).getId();
            }
        }
        catch (NoSuchElementException ex) {

        }
        return domainId;
    }

    public DecoratedSearchResultSet performDumbQuery(Context ctx, EdgeSearchProfile profile, IndexBlock block, int limitPerDomain, int limitTotal, String... termsInclude) {
        List<EdgeSearchSubquery> sqs = new ArrayList<>();

        sqs.add(new EdgeSearchSubquery(Arrays.asList(termsInclude), Collections.emptyList(), block));

        EdgeSearchSpecification specs = new EdgeSearchSpecification(profile.buckets, sqs, 100, limitPerDomain, limitTotal, "", SearchOrder.ASCENDING, EdgeSearchProfile.YOLO.equals(profile), false);

        return performQuery(ctx, new EdgeSearchQuery(specs), true);
    }

    private DecoratedSearchResultSet performQuery(Context ctx, EdgeSearchQuery processedQuery, boolean asFastAsPossible) {

        AccumulatedQueryResults queryResults = new AccumulatedQueryResults();
        UrlDeduplicator deduplicator = new UrlDeduplicator(processedQuery.specs.limitByDomain);

        if (processedQuery.searchTermsHuman.size()<=4 && !asFastAsPossible) {
            fetchResultsMulti(ctx, processedQuery, queryResults, deduplicator);
        }
        else {
            fetchResultsSimple(ctx, processedQuery, queryResults, deduplicator);
        }

        List<EdgeUrlDetails> resultList = new ArrayList<>(queryResults.size());

        for (var details : queryResults.results) {
            if (details.getUrlQuality() < -100) {
                continue;
            }
            var scoreAdjustment = adjustScoreBasedOnQuery(details, processedQuery.specs);
            details = details.withUrlQualityAdjustment(scoreAdjustment);

            resultList.add(details);
        }

        resultList.sort(resultListComparator);

        return new DecoratedSearchResultSet(resultList);
    }

    private List<String> getProblems(Context ctx, String humanQuery, String evalResult, DecoratedSearchResultSet queryResults, EdgeSearchQuery processedQuery) {
        final List<String> problems = new ArrayList<>(processedQuery.problems);
        boolean siteSearch = processedQuery.domain != null;

        if (!siteSearch) {
            if (queryResults.size() <= 5 && null == evalResult) {
                spellCheckTerms(ctx, processedQuery).forEach(problems::add);
            }

            if (queryResults.size() <= 5) {
                problems.add("Try rephrasing the query, changing the word order or using synonyms to get different results. <a href=\"https://memex.marginalia.nu/projects/edge/search-tips.gmi\">Tips</a>.");
            }

            if (humanQuery.toLowerCase().matches(".*(definition|define).*")) {
                problems.add("Tip: Try using a query that looks like <tt>define:word</tt> if you want a dictionary definition");
            }
        }

        if (humanQuery.contains("/")) {
            problems.clear();
            problems.add("<b>There is a known bug with search terms that contain a slash that causes them to be marked as unsupported; as a workaround, try using a dash instead. AC-DC will work, AC/DC does not.</b>");
        }

        return problems;
    }


    private EdgePageScoreAdjustment adjustScoreBasedOnQuery(EdgeUrlDetails p, EdgeSearchSpecification specs) {
        String titleLC = p.title == null ? "" : p.title.toLowerCase();
        String descLC = p.description == null ? "" : p.description.toLowerCase();
        String urlLC = p.url == null ? "" : p.url.path.toLowerCase();
        String domainLC = p.url == null ? "" : p.url.domain.toString().toLowerCase();

        String[] searchTermsLC = specs.subqueries.get(0).searchTermsInclude.stream()
                .map(String::toLowerCase)
                .flatMap(s -> Arrays.stream(s.split("_")))
                .toArray(String[]::new);
        int termCount = searchTermsLC.length;

        String[] titleParts = titleLC.split("[:!|./]|(\\s-|-\\s)|\\s{2,}");
        double titleHitsAdj = 0.;
        for (String titlePart : titleParts) {
            titleHitsAdj += Arrays.stream(searchTermsLC).filter(titlePart::contains).mapToInt(String::length).sum()
                    / (double) Math.max(1, titlePart.trim().length());
        }

        double titleFullHit = 0.;
        if (termCount > 1 && titleLC.contains(specs.humanQuery.replaceAll("\"", "").toLowerCase())) {
            titleFullHit = termCount;
        }
        long descHits = Arrays.stream(searchTermsLC).filter(descLC::contains).count();
        long urlHits = Arrays.stream(searchTermsLC).filter(urlLC::contains).count();
        long domainHits = Arrays.stream(searchTermsLC).filter(domainLC::contains).count();

        double descHitsAdj = 0.;
        for (String word : descLC.split("[^\\w]+")) {
            descHitsAdj += Arrays.stream(searchTermsLC)
                    .filter(term -> term.length() > word.length())
                    .filter(term -> term.contains(word))
                    .mapToDouble(term -> word.length() / (double) term.length())
                    .sum();
        }

        return EdgePageScoreAdjustment.builder()
                .descAdj(Math.min(termCount, descHits) / (10. * termCount))
                .descHitsAdj(descHitsAdj / 10.)
                .domainAdj(2 * Math.min(termCount, domainHits) / (double) termCount)
                .urlAdj(Math.min(termCount, urlHits) / (10. * termCount))
                .titleAdj(5 * titleHitsAdj / (Math.max(1, titleParts.length) * Math.log(titleLC.length() + 2)))
                .titleFullHit(titleFullHit)
                .build();
    }

    @NotNull
    private Observable<WikiArticles> getWikiArticle(Context ctx, String humanQuery) {
        return assistantClient
                .encyclopediaLookup(ctx,
                        humanQuery.replaceAll("\\s+", "_")
                                .replaceAll("\"", "")
                ).subscribeOn(Schedulers.io());
    }

    private void fetchResultsMulti(Context ctx, EdgeSearchQuery processedQuery, AccumulatedQueryResults queryResults, UrlDeduplicator deduplicator) {

        boolean debug = processedQuery.specs.subqueries.get(0).searchTermsExclude.contains("special:debug");

        var blocksOrder = processedQuery.specs.subqueries.stream().map(sq -> sq.block).distinct().sorted(Comparator.comparing(block -> block.sortOrder)).toList();

        EdgeSearchSpecification[] specsArray =
                processedQuery.specs.subqueries.stream()
                        .filter(sq -> sq.block == IndexBlock.TitleKeywords)
                        .map(sq -> processedQuery.specs.withSubqueries(blocksOrder.stream().map(sq::withBlock).collect(Collectors.toList())))
                        //.flatMap(specs -> processedQuery.specs.buckets.stream().map(bucket -> specs.withBuckets(List.of(bucket))))
                        .toArray(EdgeSearchSpecification[]::new);
        var resultSets = indexClient.multiQuery(ctx, specsArray);

        if (debug) {
            for (var s : specsArray) {
                logger.info("{}", s);
            }
            for (IndexBlock block : indexBlockSearchOrder) {
                resultSets.forEach(res -> {
                    res.resultsList.getOrDefault(block, Collections.emptyList()).forEach(b2 -> {
                        b2.results.forEach((idx,items) -> {
                            items.forEach(i ->
                                logger.info("{} {} - {}", block, idx, i)
                            );
                        });
                    });
                });
            }
        }

        Set<EdgeId<EdgeUrl>> seenUrls = new HashSet<>();
        for (IndexBlock block : indexBlockSearchOrder) {
            var resultsJoined = resultSets.stream().flatMap(rs -> rs.resultsList.getOrDefault(block, Collections.emptyList()).stream())
                    .map(EdgeSearchResults::getResults)
                    .flatMap(m -> m.entrySet().stream())
                    .flatMap(m -> m.getValue().stream())
                    .sorted(Comparator.comparing(item -> preEvaluateItem(item, block)))
                    .filter(item -> seenUrls.add(item.url))
                    .collect(Collectors.toList());

            queryResults.append( 100, resultDecorator.decorateSearchResults(resultsJoined, block, deduplicator));

            if (debug) {
                logger.info("{} -> {} items", resultsJoined, queryResults.size());
            }

        }
        if (debug) {
            logger.info("-> {} items", queryResults.size());
        }


    }

    private final WeakHashMap<EdgeSearchResultItem, Double> scoreCache = new WeakHashMap<>();
    private double preEvaluateItem(EdgeSearchResultItem item, IndexBlock block) {
        synchronized (scoreCache) {
            return scoreCache.computeIfAbsent(item, i -> valuator.evaluateTerms(i.scores, block, 1000));
        }
    }

    private void fetchResultsSimple(Context ctx, EdgeSearchQuery processedQuery, AccumulatedQueryResults queryResults, UrlDeduplicator deduplicator) {
        var resultSet = indexClient.query(ctx, processedQuery.specs);

        logger.debug("{}", resultSet);

        for (IndexBlock block : indexBlockSearchOrder) {
            for (var results : resultSet.resultsList.getOrDefault(block, Collections.emptyList())) {
                var items = results.getAllItems();
                queryResults.append(100, resultDecorator.decorateSearchResults(items, block, deduplicator));
            }
        }
    }

    static final IndexBlock[] indexBlockSearchOrder = Arrays.stream(IndexBlock.values()).sorted(Comparator.comparing(i -> i.sortOrder)).toArray(IndexBlock[]::new);

    private Iterable<String> spellCheckTerms(Context ctx, EdgeSearchQuery disjointedQuery) {
        return Observable.fromIterable(disjointedQuery.searchTermsHuman)
                .subscribeOn(Schedulers.io())
                .flatMap(term -> assistantClient.spellCheck(ctx, term)
                        .onErrorReturn(e -> Collections.emptyList())
                        .filter(results -> hasSpellSuggestions(term, results))
                        .map(suggestions -> searchTermToProblemDescription(term, suggestions))
                )
                .blockingIterable();
    }

    private boolean hasSpellSuggestions(String term, List<String> results) {
        if (results.size() > 1) {
            return true;
        }
        else if (results.size() == 1) {
            return !term.equalsIgnoreCase(results.get(0));
        }
        return false;
    }

    private String searchTermToProblemDescription(String term, List<String> suggestions) {
        return "\"" + term + "\" could be spelled " +
                suggestions.stream().map(s -> "\""+s+"\"").collect(Collectors.joining(", "));
    }


}
