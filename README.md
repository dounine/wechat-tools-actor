![](https://github.com/dounine/wechat-tools-actor/workflows/Scala%20CI/badge.svg) ![](https://img.shields.io/github/license/dounine/wechat-tools-actor)

# wechat-tools-actor
# 打包
## Gradle
普通压缩包
```shell
./gradlew clean build -Denv=prod
```
Docker运行包
```shell
./gradlew clean build docker -Denv=prod
```

## SBT
> bug: 无法将prod里面的配置文件打到jar包中

普通压缩包
```shell
sbt clean package -Denv=prod
```
Docker运行包
```
sbt clean docker:publishLocal -Denv=prod
```
运行
```shell
docker run --rm -ti wechat-tools-actor:0.1.0-SNAPSHOT
#或
docker run --rm -d -p 50001:8080 -v /etc/localtime:/etc/localtime --name wechat-tools-actor com.dounine.jb/wechat-tools-actor:1.0.0
```
sbt下载依赖包慢
```
export ANT_OPTS="-Dhttp.proxyHost=192.168.0.35 -Dhttp.proxyPort=1087" && sbt clean package -Denv=prod
```
