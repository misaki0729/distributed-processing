## Run on local

```
$ mvn spring-boot:run
```

Call this service

```
$ curl -v -F 'file=@foo.png' localhost:8080/duker.png > foo.png
```
