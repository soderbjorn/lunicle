# Lunicle

Lunicle is a self-hosted issue tracker.

**Please go to [www.lunicle.dev](https://www.lunicle.dev) for more information.**

## Introduction

Lunicle is a web-based issue tracker that supports the usual features you would expect, like issues, comments, attachments, assignees, child issues (filling the same role as epics in some other issue trackers), planned/fix versions, labels and components. It also has an MCP so your agent can work on the issues.

Lunicle is delivered via Docker. For persistence it uses uses either an embedded database or Google Cloud Firestore. Personally I use (and recommend) [Railway](https://railway.com/). The vanilla distribution can be adapted to some extent with custom themes and some settings.

This is a fast-moving, agent-first software development project. If I put too much detail here, it would quickly become obsolete. If you want specifics about the features, source code and the architecture, ask your agent!

## Tech stack

I will mention just a few words about tech choices.

I use Kotlin anywhere I can, because I really like the language, and Kotlin Multiplatform makes it easy to share code across both the server and web (and mobile apps later, too, if I should decide to make them). I however do **not** use Compose Multiplatform because I want each platform to have a native UI. For web (primarily), I use a dedicated UI toolkit ([Lunula](https://github.com/soderbjorn/lunula)) which I use also for other apps.

In projects on multiple platforms, I try to have common view models across all clients that expose a single state object per screen/view, with thin
wrappers where needed on each platform. I also re-use the Kotlin networking layer across all platforms.

## Author

[Robert Söderbjörn](https://www.soderbjorn.se) is the creator and maintainer of this project. If you would like to contribute, you are more than welcome! You can reach out at lunicle@soderbjorn.se. 

## Development

We use the [Lunicle issue tracker](https://issues.lunicle.dev/?projectId=2) itself for managing development -- of course! (it's also embedded on the Lunicle website [here](https://www.lunicle.dev/#/issues)). You can see all issues without signing in. Contact me if you would like edit rights to the board so that you can create, move and comment on tickets and to add pull requests on GitHub. Before embarking on huge re-work (rather than bug fixes or small features), you might want to talk to me first. I'm very open to significant changes as well, I just want us to agree on the UX and make sure it's done in a way that fits the vision.

## License

Lunicle is released under the [MIT License](LICENSE).

Third-party dependencies are used under their respective licenses.
