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
            if (v.status == 200) {
                processor(await v.text());
            } else if (v.status == 204) {
                return; // The stream has finished
            }
        } catch (e) {
            await sleep(200)
        }
    }
}