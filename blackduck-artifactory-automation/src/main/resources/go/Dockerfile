FROM golang:1.16.3

ENV GOPROXY="http://<username>:<password>@<server>/artifactory/api/go/<repo-key>"

RUN mkdir test
WORKDIR test

COPY go.mod ./
COPY hello.go ./
RUN go build