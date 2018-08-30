const moment = require('moment');
const { exec } = require('child_process');
const { promisify } = require('util');

const asyncExec = promisify(exec);
const {
    deleteWebhook,
    fromObjectPath,
    getProp,
    getWebhookUrl,
    hubClientChannelRefresh,
    hubClientDelete,
    hubClientGet,
    hubClientPostTestItem,
    hubClientPut,
    itSleeps,
    randomChannelName,
    randomString,
    startServer,
    waitForCondition,
} = require('../lib/helpers');
const {
    getCallBackDomain,
    getCallBackPort,
    getHubUrlBase,
    getChannelUrl,
} = require('../lib/config');

const {
    RESTART_ZOOKEEPERS,
} = process.env;

const port = getCallBackPort();
const channelName = randomChannelName();
const webhookName = randomChannelName();
const callbackDomain = getCallBackDomain();
const callbackPath = `/${randomString(5)}`;
const callbackUrl = `${callbackDomain}:${port}${callbackPath}`;
const internalPath = `${getHubUrlBase()}/internal/properties`;
// const { rollingRestartData: testData } = require('../path-to-static-data');
const bigString = () => new Array(1000).fill(randomString(4)).join();
const testData = () => new Array(100).fill(bigString());
const channelResource = `${getChannelUrl()}/${channelName}`;
const testContext = {
    [channelName]: {
        postedItemHistory: [],
        callbackItemHistory: [],
        serversToRestart: [],
        zookeepersToRestart: [],
    },
};
const mutableTime = moment().subtract(1, 'minute');
const timeFormat = 'YYYY-MM-DDTHH:mm:ss.SSS';
const channelBody = {
    mutableTime: mutableTime.format(timeFormat),
};
const channelBodyChange = {
    mutableTime: moment(mutableTime).subtract(10, 'minutes').format(timeFormat),
};
const headers = { 'Content-Type': 'application/json' };
const getZookeepers = (body) => {
    const properties = getProp('properties', body) || {};
    const zks = properties['zookeeper.connection'];
    return zks ? zks.split(',') : [];
};

const failIfNotReady = () => {
    if (!testContext[channelName].ready) {
        return fail('test configuration failed in before block');
    }
};

describe('stability of webhook delivery during restart of the hub', () => {
    beforeAll(async () => {
        // make a call to the hub to clarify it is alive
        const response1 = await hubClientGet(`${getHubUrlBase()}/channel`);
        const stableStart = getProp('statusCode', response1) === 200;
        // configure the test based on hub properties
        // not necessary for single hub
        // may not be the right path for clustered hub envs
        // =============begin
        const internal = hubClientGet(internalPath, headers);
        const properties = getProp('body', internal) || {};
        const isClustered = properties['hub.type'] === 'aws';
        if (isClustered) {
            const serversToRestart = getProp('servers', properties);
            testContext[channelName].serversToRestart.push(...serversToRestart);
            if (RESTART_ZOOKEEPERS) {
                const zookeepers = getZookeepers(properties);
                testContext[channelName].zookeepersToRestart.push(...zookeepers);
            }
        }
        // =============end
        // create a historical channel
        // sets mutableTime
        const response2 = await hubClientPut(channelResource, headers, channelBody);
        const channelStart = getProp('statusCode', response2) === 201;
        // start a callback server
        const callback = (item) => {
            console.log('callback: ', item);
            testContext[channelName].callbackItemHistory.push(item);
        };
        const response3 = await startServer(port, callback, callbackPath);
        const serverStart = !!response3;
        testContext[channelName].callbackServer = response3;
        // tag all as ready to roll
        testContext[channelName].ready = [stableStart, channelStart, serverStart]
            .every(t => t);
    });

    it('posts a start item', async () => {
        failIfNotReady();
        const beforeMutable = moment(mutableTime).subtract(4, 'minutes');
        const pointInThePastURL = `${channelResource}/${beforeMutable.format('YYYY/MM/DD/HH/mm/ss/SSS')}`;
        const response = await hubClientPostTestItem(pointInThePastURL, headers, testData());
        const item = fromObjectPath(['body', '_links', 'self', 'href'], response);
        process.stdout.write(`
        ‹‹‹‹‹‹‹‹startItem››››››››
        ${item}
        ‹‹‹‹‹‹‹‹‹‹‹‹‹›››››››››››››`
        );
        testContext[channelName].firstItem = item;
    });

    it('post loads of data after mutableTime', async () => {
        failIfNotReady();
        for (const data of testData()) {
            const response = await hubClientPostTestItem(channelResource, headers, data);
            const item = fromObjectPath(['body', '_links', 'self', 'href'], response);
            testContext[channelName].postedItemHistory.push(item);
        }
    });

    it('post loads of data before mutableTime', async () => {
        failIfNotReady();
        let millis = 1;
        for (const data of testData()) {
            millis += 10;
            const beforeMutable = moment(mutableTime).subtract(millis, 'milliseconds');
            const pointInThePastURL = `${channelResource}/${beforeMutable.format('YYYY/MM/DD/HH/mm/ss/SSS')}`;
            const response = await hubClientPostTestItem(pointInThePastURL, headers, data);
            const item = fromObjectPath(['body', '_links', 'self', 'href'], response);
            testContext[channelName].postedItemHistory.unshift(item);
        }
    });

    it('changes mutableTime to before earliest item', async () => {
        failIfNotReady();
        const response = await hubClientPut(channelResource, headers, channelBodyChange);
        expect(getProp('statusCode', response)).toEqual(201);
    });

    it('waits while the channel is refreshed', async () => {
        failIfNotReady();
        const response = await hubClientChannelRefresh();
        expect(getProp('statusCode', response)).toEqual(200);
    });

    it('creates a webhook pointing with startItem set to earliest posted', async () => {
        failIfNotReady();
        const url = `${getWebhookUrl()}/${webhookName}`;
        console.log('webhookUrl****', url);
        const body = {
            callbackUrl,
            channelUrl: channelResource,
            startItem: testContext[channelName].firstItem,
        };

        const response = await hubClientPut(url, headers, body);
        expect(getProp('statusCode', response)).toBe(201);
    });

    it('triggers a restart of the single hub', async () => {
        failIfNotReady();
        // get docker's hub id
        const getHubIdCMD = 'docker ps --filter ancestor="flightstats/hub" --no-trunc --format "{{.ID}}"';
        let hubId = '';
        try {
            const { stdout, stderr } = await asyncExec(getHubIdCMD);
            if (stderr) {
                console.log('stderr', stderr);
                expect(stderr).not.toBeDefined();
            }
            if (stdout) {
                console.log('stdout', stdout);
                hubId = stdout && stdout.trim();
            }
        } catch (ex) {
            console.log('error', ex);
            expect(ex).not.toBeDefined();
        }
        expect(hubId).toBeDefined();
        // use the id to restart the hub
        try {
            const { stdout, stderr } = await asyncExec(`docker restart ${hubId}`);
            if (stderr) {
                console.log('stderr', stderr);
                expect(stderr).not.toBeDefined();
            }
            if (stdout) {
                console.log('stdout', stdout);
                const restartResponse = stdout && stdout.trim();
                expect(restartResponse).toEqual(hubId);
            }
        } catch (ex) {
            console.log('error', ex);
            expect(ex).not.toBeDefined();
        }
        const { callbackItemHistory } = testContext[channelName];
        testContext[channelName].calledBackBeforeRestart = callbackItemHistory.length;
    });

    xit('triggers a rolling restart of the hub cluster', async () => {
        // pseudo code for clustered hub, zk env
        const restartCallback = async (error, stdout, stderr) => {
            if (error) {
                console.error(`exec error: ${error}`);
                return null;
            }
            console.log(`stdout: ${stdout}`);
            console.log(`stderr: ${stderr}`);
            return stdout;
        };
        const {
            serversToRestart,
            zookeepersToRestart,
        } = testContext[channelName];
        for (const SERVER of serversToRestart) {
            const restart = await exec('$HOME/restart_hub_docker.sh', {
                env: {
                    SERVER,
                    ...process.env,
                },
            }, restartCallback);
            expect(restart).toBeDefined();
        }

        if (RESTART_ZOOKEEPERS) {
            for (const ZK_URL of zookeepersToRestart) {
                const restart = await exec('$HOME/restart_zk_docker.sh', {
                    env: {
                        ZK_URL,
                        ...process.env,
                    },
                }, restartCallback);
                expect(restart).toBeDefined();
            }
        }
    });

    it('waits for the hub to be back up', async () => {
        failIfNotReady();
        // poll the channel url for 50 seconds, check for 200
        let statusCode = 0;
        let tries = 0;
        do {
            tries += 1;
            await itSleeps(500);
            const response = await hubClientGet(channelResource);
            statusCode = getProp('statusCode', response);
        } while (statusCode !== 200 && tries < 101);
    });

    xit('waits for all the callbacks to happen', async () => {
        failIfNotReady();
        const {
            callbackItemHistory,
            postedItemHistory,
        } = testContext[channelName];
        const condition = () => (
            callbackItemHistory.length ===
            postedItemHistory.length
        );
        await waitForCondition(condition);
        console.log('callbacks made', callbackItemHistory.length);
    });

    it('verifies the number of items called back', async () => {
        failIfNotReady();
        const {
            calledBackBeforeRestart,
            callbackItemHistory,
        } = testContext[channelName];
        const condition = () => (
            callbackItemHistory.length ===
            calledBackBeforeRestart
        );
        await waitForCondition(condition);
        console.log('callbacks made', callbackItemHistory.length);
    });

    xit('verifies callbacks that were made were made in proper historical order', () => {
        failIfNotReady();
        const {
            callbackItemHistory,
            postedItemHistory,
        } = testContext[channelName];
        const actual = postedItemHistory.every((data, index) => {
            const same = callbackItemHistory[index] === data;
            if (!same) console.log('not same', data, callbackItemHistory[index], index);
            return same;
        });
        expect(actual).toBe(true);
    });

    it('verifies callbacks that were made were made in proper historical order', () => {
        failIfNotReady();
        const {
            callbackItemHistory,
            calledBackBeforeRestart,
            postedItemHistory,
        } = testContext[channelName];
        const postedItems = postedItemHistory.splice(0, calledBackBeforeRestart);
        const actual = postedItems.every((data, index) => {
            const same = callbackItemHistory[index] === data;
            if (!same) console.log('not same', data, callbackItemHistory[index], index);
            return same;
        });
        expect(actual).toBe(true);
    });

    it('calls for latest from the channel and gets a 404 for some reason', async () => {
        const response = await hubClientGet(`${channelResource}/latest`, headers);
        expect(getProp('statusCode', response)).toEqual(404);
    });

    it('calls the lastCompleted url in the webhook and it is not found', async () => {
        const url = `${getWebhookUrl()}/${webhookName}`;
        const response1 = await hubClientGet(url, headers);
        const lastCompletedUrl = fromObjectPath(['body', 'lastCompleted'], response1) || [];
        const actual = testContext[channelName].postedItemHistory.every(item => item !== lastCompletedUrl);
        expect(actual).toBe(true);
        const response2 = await hubClientGet(lastCompletedUrl, headers);
        expect(getProp('statusCode', response2)).toEqual(404);
    });

    it('channel does not have the expected items', async () => {
        const { postedItemHistory } = testContext[channelName];
        const lastItemPosted = postedItemHistory.pop();
        const response = await hubClientGet(lastItemPosted);
        expect(getProp('statusCode', response)).toEqual(404);
        const previousUrl = `${lastItemPosted}/previous/5`;
        const response1 = await hubClientGet(previousUrl, headers);
        const uris = fromObjectPath(['body', '_links', 'uris'], response1);
        expect(Array.isArray(uris) && uris.length === 0).toBe(true);
        const response2 = await hubClientGet(`${lastItemPosted}/previous`);
        expect(getProp('statusCode', response2)).toEqual(404);
    });

    afterAll(async () => {
        await hubClientDelete(channelResource);
        await deleteWebhook(webhookName);
    });
});
