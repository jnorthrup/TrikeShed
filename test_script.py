import json

request1 = """
        {
          "prompt": "implement",
          "title": "title",
          "sourceContext": {
            "source": "sources/github/jnorthrup/TrikeShed",
            "githubRepoContext": { "startingBranch": "master" }
          }
        }
        """

print("implement" in request1)
