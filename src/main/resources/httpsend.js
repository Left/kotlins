function httpGet(url) {
    let xhr = new XMLHttpRequest();
    xhr.open("GET", url, true);
    xhr.setRequestHeader("Content-Type", "application/json");
    xhr.onreadystatechange = function () {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                // alert("OK: " + xhr.responseText);
                // location.reload();
            } else {
                // alert("Error \"" + xhr.responseText + "\"");
            }
        }
    };
    xhr.send(JSON.stringify({ }));
}