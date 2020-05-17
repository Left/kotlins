#!/bin/bash
mvn package wagon:upload-single@upload-to-myserver
# ssh root@vadimkharkov.keenetic.link -p13086 'systemctl restart kotlins'
ssh root@192.168.121.38 'systemctl restart kotlins'
