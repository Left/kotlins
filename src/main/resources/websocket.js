var reconnectInterval;
var sock;
var serverVersion;

function start() {
    sock = new WebSocket("ws:/" + location.host + "/web");

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
            reconnectInterval = setInterval(() => { start(); }, 2000);
        }
    }
    sock.onerror = reconnect;
    sock.onclose = reconnect;
}
start();