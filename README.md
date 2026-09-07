# Unciv4iOS - Civ V remake for iOS

![](/extraImages/GithubPreviewImage.jpg)

> This is an **iOS port fork** of [yairm210/Unciv](https://github.com/yairm210/Unciv).
> Upstream Unciv supports Android, Desktop and Web, but not iOS.
> This repository exists to make Unciv run natively on iPhone and iPad.
> Game content and the modding ecosystem stay in sync with upstream, and the license is the same MPL 2.0.
> The iOS port source is published here under MPL 2.0.

[![TestFlight](https://img.shields.io/badge/TestFlight-Join%20Beta-blue?logo=apple)](https://testflight.apple.com/join/XSgMMQjt)
![Platform](https://img.shields.io/badge/platform-iOS-blue?logo=apple)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![LibGDX](https://img.shields.io/badge/LibGDX-e44d3c?logo=libgdx&logoColor=white)
![License](https://img.shields.io/badge/license-MPL--2.0-green)
[![Forked from Unciv](https://img.shields.io/badge/forked%20from-yairm210%2FUnciv-blueviolet)](https://github.com/yairm210/Unciv)
[![Discord](https://img.shields.io/discord/586194543280390151?color=%237289DA&logo=discord&logoColor=%23FFFFFF)](https://discord.gg/bjrB4Xw)

## What is this?

An open source, moddability-focused **iOS** remake of Civ V, made with [LibGDX](https://github.com/libgdx/libgdx).

This is a fork of [Unciv](https://github.com/yairm210/Unciv). The upstream project does not support iOS,
so everything iOS-specific is developed here.

## Is this any good?

Depends what you're looking for. If you're in the market for high-res graphics, amazing soundtracks, animations etc, I highly recommend Firaxis's Civ-V-like game, "Civilization V".

If you want a small, fast, moddable, FOSS, in-depth 4X that can still run on a potato, you've come to the right place :)

## How do I install on iOS?

### Join the TestFlight beta

The quickest way to try it: **[join the public TestFlight beta](https://testflight.apple.com/join/XSgMMQjt)**

This is a beta build of the iOS port. Expect some bugs and missing features -
see [What's the roadmap?](#whats-the-roadmap) for what works today.

### App Store

Not available yet - the App Store release is planned once the port is stable.

### Build from source

The `ios/` module is published here under MPL 2.0 - see
[ios/README.md](ios/README.md) for the RoboVM build, simulator and signing
requirements.

## What's the roadmap?

The iOS port is built in two phases, both of which are now complete:

**Phase 1 - Single player (complete)**

A fully playable single player game runs on iPhone and iPad:

* Touch-first UI+UX improvements ([suggestions welcome!](https://github.com/jerry8870/Unciv4iOS/issues/new?assignees=&labels=feature&template=feature_request.md&title=Feature+request%3A+))
* Save games, mods and assets working correctly under the iOS sandbox
* Performance and stability on iPhone and iPad

**Phase 2 - Multiplayer (complete)**

Multiplayer now works in the foreground over public HTTPS API v1 servers - by
default [uncivserver.xyz](https://uncivserver.xyz), or any custom public HTTPS
server you enter in the game. See [how multiplayer works upstream](https://yairm210.github.io/Unciv/Other/Multiplayer/).

Note: Dropbox-backed multiplayer has been removed upstream and is not supported on iOS.

**Mod management (complete)**

In-app Mod management is now available: the Mods screen lists the GitHub
catalog, and Mods can be downloaded by URL, installed, updated and removed
from inside the app. Missing Mods can also be downloaded when loading a save.
Downloads run through an NSURLSession-backed streaming engine under Ktor - see
[ios/README.md](ios/README.md#platform-capability-boundary) for the capability
boundary.

After that, we follow upstream:

* G&K mechanics - see [upstream #4697](https://www.github.com/yairm210/Unciv/issues/4697)
* BNW mechanics - trade routes, world congress, etc.

Some things are still missing on iOS: multiplayer chat, push notifications,
background polling, and HTTP/LAN multiplayer servers.

## Contributing

Programmers start [here](https://yairm210.github.io/Unciv/Developers/Building-Locally/)!

Translators start [here](https://yairm210.github.io/Unciv/Translating/Translating/)! Language completion status [here](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/translations/completionPercentages.properties) 

Modders start [here](https://yairm210.github.io/Unciv/Modders/Mods/)!

You can join us in any of the open issues, or work on improving anything you want - once you're finished, issue a pull request and it'll go into the next version!

If not, you can help by spreading the word - vote for Unciv where you can, mention it on Reddit or Twitter etc, and help us with new ideas of how to get the word out!


## FAQ

### Why a separate iOS fork?

Upstream Unciv is not planning an iOS release - it means paying money to Apple, yet another release path,
and without an iOS device it cannot be tested properly. This fork exists to do exactly that,
so all iOS-specific work lives here instead.

### Steam release?

Steam has decided that they don't want to host Unciv, they probably don't want to risk legal issues with Firaxis (although those should be non-existent, see below).
 
### Will you implement {feature}?

If it's in the original Civ V, then yes!

If not, then the feature won't be added to the base game - possibly it will be added as a way to mod the game, which is constantly expanding.

#### Why not? This is its own game, why not add features that weren't in Civ V?

Having a clear vision is important for actually getting things done.

Anyone can make a suggestion. Not all are good, viable, or simple. Not many can actually implement stuff.

As an open source project, this stuff is done in our spare time, of which there isn't much.

We need a clear-cut criteria to decide what to work on and what not to work on.

#### Will you implement Civ VI?

Considering how long it took to get this far, no.

### How can I learn to play? Where's the wiki?

All the tutorial information is available in-game at menu > civilopedia > tutorials

All the information is included in the amazing [Civ V wiki](https://civilization.fandom.com/wiki/)

Since this is a Civ V clone, you can search Google for how to play Civ V and there are loads of answers =)

Alternatively, you could [join us on Discord](https://discord.gg/bjrB4Xw) and ask there =D

### Aren't you basically making a Civ V clone? Is that even legal?

According to the [US Copyright Office FL-108](https://upload.wikimedia.org/wikipedia/commons/9/96/U.S._Copyright_Office_fl108.pdf), intellectual property rights *do not* apply to mechanics - as I'm sure you know, there are a billion Flappy Bird knockoffs.

It is definitely illegal:
 - To use any assets from the original game (images, sound etc) - they belong to Firaxis

It is probably illegal (no solid sources on this):
 - To use the Civilization name
 - To impersonate the Civ games (so calling yourself civi|zation with a similar logo, for instance)

Interestingly, [Civilization is a registered trademark](https://tsdr.uspto.gov/#caseNumber=74166752&caseType=SERIAL_NO&searchType=statusSearch), but it looks like it's only *that particular logo* which is trademarked, so technically you could make another game called "Civilization" and it'll stick. In any case we're not going there :) 

## Licensing and credits

This game is available under the MPL 2.0 (see [LICENSE](LICENSE]). The media
files are authored by many people, available under a mix of CC BY-SA 4.0,
CC BY 3.0/4.0, CC0, Public Domain (see [docs/Credits.md](docs/Credits.md)).

This repository is a **modified version** of [yairm210/Unciv](https://github.com/yairm210/Unciv),
Copyright (c) Yair Morgenstern, licensed under the same MPL 2.0.
The changes in this fork are limited to adding iOS support, and are documented in the commit history.
