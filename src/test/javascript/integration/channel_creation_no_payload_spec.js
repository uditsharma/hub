require('../integration_config');
const { getStatusCode } = require('../lib/helpers');

describe(__filename, function () {

	it('creates a channel with no payload', function (done) {
		var url = channelUrl;
		var headers = {'Content-Type': 'application/json'};
		var body = '';

		utils.httpPost(url, headers, body)
			.then(function (response) {
				expect(getStatusCode(response)).toEqual(400);
			})
			.finally(done);
	});

});
