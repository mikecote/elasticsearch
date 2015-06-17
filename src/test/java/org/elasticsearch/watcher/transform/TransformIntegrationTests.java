/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transform;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.watcher.support.Script;
import org.elasticsearch.watcher.test.AbstractWatcherIntegrationTests;
import org.elasticsearch.watcher.test.WatcherTestUtils;
import org.elasticsearch.watcher.transport.actions.put.PutWatchResponse;
import org.junit.Test;

import java.net.URISyntaxException;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.watcher.actions.ActionBuilders.indexAction;
import static org.elasticsearch.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.watcher.condition.ConditionBuilders.alwaysCondition;
import static org.elasticsearch.watcher.input.InputBuilders.searchInput;
import static org.elasticsearch.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.watcher.transform.TransformBuilders.*;
import static org.elasticsearch.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.*;

/**
 */
public class TransformIntegrationTests extends AbstractWatcherIntegrationTests {

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        //Set path so ScriptService will pick up the test scripts
        try {
            return settingsBuilder().put(super.nodeSettings(nodeOrdinal))
                    .put("path.conf", TransformIntegrationTests.class.getResource("/config").toURI().getPath()).build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testScriptTransform() throws Exception {
        final Script script;
        if (randomBoolean()) {
            logger.info("testing script transform with an inline script");
            script = Script.inline("return [key3 : ctx.payload.key1 + ctx.payload.key2]").lang("groovy").build();
        } else if (randomBoolean()) {
            logger.info("testing script transform with an indexed script");
            client().preparePutIndexedScript("groovy", "_id", "{\"script\" : \"return [key3 : ctx.payload.key1 + ctx.payload.key2]\"}").get();
            script = Script.indexed("_id").lang("groovy").build();
        } else {
            logger.info("testing script transform with a file script");
            script = Script.file("my-script").lang("groovy").build();
        }

        // put a watch that has watch level transform:
        PutWatchResponse putWatchResponse = watcherClient().preparePutWatch("_id1")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(alwaysCondition())
                        .transform(scriptTransform(script))
                        .addAction("_id", indexAction("output1", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));
        // put a watch that has a action level transform:
        putWatchResponse = watcherClient().preparePutWatch("_id2")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(alwaysCondition())
                        .addAction("_id", scriptTransform(script), indexAction("output2", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id1");
            timeWarp().scheduler().trigger("_id2");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id1", 1, false);
        assertWatchWithMinimumPerformedActionsCount("_id2", 1, false);
        refresh();

        SearchResponse response = client().prepareSearch("output1").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1l));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key3").toString(), equalTo("20"));

        response = client().prepareSearch("output2").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1l));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key3").toString(), equalTo("20"));
    }

    @Test
    public void testSearchTransform() throws Exception {
        createIndex("my-condition-index", "my-payload-index");
        ensureGreen("my-condition-index", "my-payload-index");

        index("my-payload-index", "payload", "mytestresult");
        refresh();

        SearchRequest inputRequest = WatcherTestUtils.newInputSearchRequest("my-condition-index").source(searchSource().query(matchAllQuery()));
        SearchRequest transformRequest = WatcherTestUtils.newInputSearchRequest("my-payload-index").source(searchSource().query(matchAllQuery()));

        PutWatchResponse putWatchResponse = watcherClient().preparePutWatch("_id1")
                .setSource(watchBuilder()
                                .trigger(schedule(interval("5s")))
                                .input(searchInput(inputRequest))
                                .transform(searchTransform(transformRequest))
                                .addAction("_id", indexAction("output1", "result"))
                ).get();
        assertThat(putWatchResponse.isCreated(), is(true));
        putWatchResponse = watcherClient().preparePutWatch("_id2")
                .setSource(watchBuilder()
                                .trigger(schedule(interval("5s")))
                                .input(searchInput(inputRequest))
                                .addAction("_id", searchTransform(transformRequest), indexAction("output2", "result"))
                ).get();
        assertThat(putWatchResponse.isCreated(), is(true));

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id1");
            timeWarp().scheduler().trigger("_id2");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id1", 1, false);
        assertWatchWithMinimumPerformedActionsCount("_id2", 1, false);
        refresh();

        SearchResponse response = client().prepareSearch("output1").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1l));
        assertThat(response.getHits().getAt(0).sourceAsString(), containsString("mytestresult"));

        response = client().prepareSearch("output2").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1l));
        assertThat(response.getHits().getAt(0).sourceAsString(), containsString("mytestresult"));
    }

    @Test
    public void testChainTransform() throws Exception {
        final Script script1 = Script.inline("return [key3 : ctx.payload.key1 + ctx.payload.key2]").lang("groovy").build();
        final Script script2 = Script.inline("return [key4 : ctx.payload.key3 + 10]").lang("groovy").build();
        // put a watch that has watch level transform:
        PutWatchResponse putWatchResponse = watcherClient().preparePutWatch("_id1")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(alwaysCondition())
                        .transform(chainTransform(scriptTransform(script1), scriptTransform(script2)))
                        .addAction("_id", indexAction("output1", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));
        // put a watch that has a action level transform:
        putWatchResponse = watcherClient().preparePutWatch("_id2")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(alwaysCondition())
                        .addAction("_id", chainTransform(scriptTransform(script1), scriptTransform(script2)), indexAction("output2", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id1");
            timeWarp().scheduler().trigger("_id2");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id1", 1, false);
        assertWatchWithMinimumPerformedActionsCount("_id2", 1, false);
        refresh();

        SearchResponse response = client().prepareSearch("output1").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1l));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key4").toString(), equalTo("30"));

        response = client().prepareSearch("output2").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1l));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key4").toString(), equalTo("30"));
    }

}
