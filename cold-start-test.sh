#!/bin/bash
KEY=`tr -dc A-Za-z0-9 </dev/urandom | head -c 13` 
cd $WK/one-time-link-aws
cp src/main/other/store.json /tmp/store.json
sed -i "s/ZZKEYZZ/$KEY/g" /tmp/store.json
date
for run in {1..10}; do
  (time curl -X POST https://onetimelink.davidmoten.org/prod/store -H "Content-Type: application/json" --data-binary "@/tmp/store.json") 2>&1| grep real
done

