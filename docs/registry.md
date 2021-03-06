
#The ActorRegistry

##Overview

The actor registry provides squbs applications an easy way to send/receive message to squbs well-known actors. 

* Well-Known actors 
* ActorLookup APIs to send/receive message to squbs well-known actors
* ActorRegistry actor which holds all well-known actor's information 


##Well Know Actor

Squbs well know actor is defined at squbs-action section of Meta-INF/squbs-meta.conf:
* class-name:		class name of the actor
* name:			registered name of the actor
* message-class:	request/response message type


Sample:

```
cube-name = org.squbs.TestCube
cube-version = "0.0.5"
squbs-actors = [
    {
      class-name = org.squbs.testcube.TestActor
      name = TestActor
      message-class = [
        {
          request = org.squbs.testcube.TestRequest
          response= org.squbs.testcube.TestResponse
        }
        {
          request = org.squbs.testcube.TestRequest1
          response= org.squbs.testcube.TestResponse1
        }
      ]
    }
]
```

##ActorLookup API

* Send message (!/?/tell/ask) to an actor which registered its request message class type as "TestRequest"

```
  implicit val system : ActorSystem = ...
  ActorLookup ! TestRequest(...)  			
  
```

* Send message (!/?/tell/ask) to an actor which registered its request message class type as "TestRequest", and response message class type as "TestResponse"
```
[remote "oldrepo"]
	url = git@github.scm.corp.ebay.com:Raptor/squbs.git
	fetch = +refs/heads/*:refs/remotes/oldrepo/*
  implicit val system : ActorSystem = ...
  ActorLookup[TestResponse] ! TestRequest(...) 	
  
```

* Send message (!/?/tell/ask) to actor which registered its name as "TestActor", and request message class type as "TestRequest"
```
  implicit val system : ActorSystem = ...
  ActorLookup("TestActor") ! TestRequest(...) 	
  
```

* Send message (!/?/tell/ask) to actor which registered name as "TestActor", request message class type as "TestRequest", and response message class type as "TestResponse"
```
  implicit val system : ActorSystem = ...
  ActorLookup[TestResponse]("TestActor") ! TestRequest(...) 	
  
```

* Resolve to actorRef which registered its response message class type as "TestResponse"
```
  implicit val system : ActorSystem = ...
  implicit val timeout : Timeout = ...
  ActorLookup[TestResponse].resolveOne
  
```
* Resolve to actorRef which registered its name as "TestActor"  
```
  implicit val system : ActorSystem = ...
  implicit val timeout : Timeout = ...
  ActorLookup("TestActor").resolveOne
  
```
* Resolve to actorRef which registered its name as "TestActor", and response message class type as "TestReponse" 
```
  implicit val system : ActorSystem = ...
  implicit val timeout : Timeout = ...
  ActorLookup[TestReponse]("TestActor").resolveOne
  
```
* Resolve to actorRef which registered its name as "TestActor", and request message class type as "TestRequest" 
```
  implicit val system : ActorSystem = ...
  implicit val timeout : Timeout = ...
  val al= new ActorLookup(requestClass=Some(classOf[TestRequest]))
  al.resolveOne
  
```

##ActorRegistry Actor

* Initialized at Unicomplex boot time, it collects each cube's well-known actor information based on each cubes Meta-INF/squbs-meta.conf file
* There is JMX Bean created for each well-known actor((org.squbs.unicomplex:type=ActorRegistry,name=%actorPath)



## Dependencies

Add the following dependency to your build.sbt or scala build file:

"org.squbs" %% "squbs-actorregistry" % squbsVersion
