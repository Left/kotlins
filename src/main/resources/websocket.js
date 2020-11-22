var reconnectInterval;
var sock;
var serverVersion;

function start(clientId) {
    sock = new WebSocket("ws:/" + location.host + "/web/" + clientId);

    sock.onopen = () => {
        console.log("Connected to websocket " + (new Date()));
        clearInterval(reconnectInterval);
        reconnectInterval = undefined;

        sock.onmessage = function(event) {
            try {
                eval(event.data);
            } catch (e) {
            }
            // console.log(event.data);
        }
    }

    function reconnect() {
        if (!reconnectInterval) {
            reconnectInterval = setInterval(() => { start(clientId); }, 2000);
        }
    }
    sock.onerror = reconnect;
    sock.onclose = reconnect;
}
start(_htmlClientId);

async function longPoll(url, processor) {
    function sleep(ms) {
      return new Promise(resolve => setTimeout(resolve, ms));
    }

    for (;;) {
        try {
            const v = await fetch(url, {
                method: 'GET', // *GET, POST, PUT, DELETE, etc.
                mode: 'cors', // no-cors, *cors, same-origin
                cache: 'no-cache', // *default, no-cache, reload, force-cache, only-if-cached
                credentials: 'same-origin', // include, *same-origin, omit
                headers: {
                    'Content-Type': 'application/json'
                },
                redirect: 'follow', // manual, *follow, error
                referrerPolicy: 'no-referrer' // no-referrer, *client
                // body: JSON.stringify(data) // body data type must match "Content-Type" header
            });
            processor(await v.json());
        } catch (e) {
            await sleep(200)
        }
    }
}