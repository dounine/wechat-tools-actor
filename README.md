![](https://github.com/dounine/wechat-tools-actor/workflows/Scala%20CI/badge.svg) ![](https://img.shields.io/github/license/dounine/wechat-tools-actor)

# wechat-tools-actor
## 打包
java -cp 运行
```
sbt clean package dist -Denv=prod
cd target/universal && unzip wechat-tools-actor-0.1.0-SNAPSHOT.zip
```
docker 方式运行
```
# 打包
sbt clean docker:publishLocal -Denv=prod
# 运行
docker run --rm -ti wechat-tools-actor:0.1.0-SNAPSHOT
```
下载包慢
```
export ANT_OPTS="-Dhttp.proxyHost=192.168.0.35 -Dhttp.proxyPort=1087" && sbt clean package -Denv=prod
```
