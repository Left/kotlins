#!/bin/bash
gradle Jar
scp build/libs/kotlinServer-1.0-SNAPSHOT.jar root@192.168.1.138:/root/kotlins/kotlinServer.jar
# ssh root@vadimkharkov.keenetic.link -p13086 'systemctl restart kotlins'
ssh root@192.168.1.138 'systemctl restart kotlins'
