/* A class to drive a Grbl instance automatically */

// Dependencies:
// 	CTK (followSynthXY)
//		ControlPlotter (plotMotorPositions)

// TODO: followSynthXY functionality could probably be replaced by bus.getSyncronous
// TODO: work out relationship between motorInstructionRate and updateStateHz
// TODO: inspect for world position offset and max travel params to calc soft limits in SC
// TODO: many of these params are machine-specific, subclass for each machine, or be able to read in settings file

GrblDriver : Grbl {
	// copyArgs
	var <grbl;

	// the fallowing variables must be defined in .initSystemParams
	var <>maxDistPerSec; 			// e.g. 180 / 3.2702541628;	// seconds to go 180 degrees
	var <>maxLagDist; 				// max units that the world can lag behind a control signal
	var <minFeed, <maxFeed;
	var <>underDrive, <>overDrive;
	var <>dropLag;					// if a move is skipped, shorten the next one so it doesn't have to make up the full distance
	var <motorInstructionRate;	// rate to send new motor destinations
	// var <>minMoveQueue;		// min moves queue'd up in buffer

	var <>catchSoftLimit	= true;		// check location before sending to GRBL to catch before soft limit to prevent ALARM
	var <softClipMargin 	= 0.5;		// offset from soft limits to clip before GRBL soft limit alarm

	var <xLimitLow, <xLimitHigh, <yLimitLow, <yLimitHigh;
	var <xClipLow, <xClipHigh, <yClipLow, <yClipHigh;

	var <followSynthXY, <followDefXY, <followResponderXY;
	var <starving = false, <lagging = false;
	var <plannerView; //, <feedSpec;
	var maxRxBufsize = 127;
	var <ui;
	var <timeLastSent, <destLastSent;
	var <throttle=1, <>autoThrottle=0.05, throttleCount=0, outOfRangeVal;
	var <>plannerMin=5, <>plannerMax=8;

	// LFO driving vars
	var lfoDrivingEnabled = false, <driving=false;
	var <lfoControlX, <lfoControlY, <plotterX, <plotterY;
	var <lfoLowX, <lfoHighX, <lfoLowY, <lfoHighY;
	var <centerX, <centerY, <rangeX, <rangeY;

	// var <>debug;

	*guiClass { ^this.subclassResponsibility(thisMethod) }

	// NOTE: overwrites Grbl's init method, see Grbl.init to see required instance vars to initialize
	init {
		// used to create a unique ID, no need to remove when freed
		Grbl.numInstances = Grbl.numInstances +1;
		id = Grbl.numInstances;

		// NOTE: rxBuf stored as a list of numbers, each being the
		// size of the string of each message.  This allows for keepng track
		// of the total characters in the rx buffer, most importantly for removing
		// values when notified that a message was processed.
		rxBuf = List();
		rxBufsize = 0;


		parser = Grbl.parserClass.new(this);
		mPos = [0,0,0];
		wPos = [0,0,0];
		// default rate that state will be requested when stateRoutine runs
		stateUpdateRate = 8;

		this.initSystemParams;
		this.prPushParams;		// update some state vars based on system params

		inputThread = fork { parser.parse };
	}

	initSystemParams { ^this.subclassResponsibility(thisMethod) }

	gui { | ... args |
		// for the moment guis are expected to have lfo support
		// so initLFO driving before creating gui
		fork( {
			var cond, xrange, yrange, xlo, xhi, ylo, yhi;
			cond = Condition();

			// this.initLfoDriving(-45, 45, -30, 30, finishedCond: cond );
			// init to half the machine range, centered
			xrange = xLimitHigh - xLimitLow;
			yrange = yLimitHigh - yLimitLow;
			xlo = xLimitLow + (xrange *0.25);
			xhi = xLimitLow + (xrange *0.75);
			ylo = yLimitLow + (yrange *0.25);
			yhi = yLimitLow + (yrange *0.75);
			this.initLfoDriving(xlo, xhi, ylo, yhi , finishedCond: cond );
			cond.wait;

			ui = this.class.guiClass.new(this, *args);
		}, clock: AppClock
		);
	}

	makeGui { ^this.subclassResponsibility(thisMethod) }

	prPushParams {
		this.updateSoftClipLimits;
		// feedSpec = ControlSpec(minFeed, maxFeed, default: 500);
	}

	planningBufGui { plannerView = GrblPlannerBufView(this) }

	// Grbl recommends not to exceed 5Hz update rate, but....
	followSerialBufXY_ { |ctlBusX, ctlBusY, driveRate, updateStateHz, planningBufGui = false|
		// var lastDest;

		"Initializing serial bus following.".postln;
		ctlBusX ?? {"Must provide an X control bus to follow".error; ^this};
		ctlBusY ?? {"Must provide a Y control bus to follow".error; ^this};

		// monitor how full the serial buffer is: starved or dropping messages
		planningBufGui.if{ this.planningBufGui };

		// start the stream buffer fresh
		this.clearRxBuf;

		// make sure stateRoutine is playing to update wPos and state variables
		stateRoutine.isPlaying.not.if{
			updateState_(true, updateStateHz);
		};

		// ensure lastDest has a value
		// lastDest	?? {
		postf("setting last dest as current wPos: %\n", wPos[0..1].asPoint);
		// lastDest = wPos[0..1].asPoint;
		destLastSent = wPos[0..1].asPoint;
		// };

		// forward driveRate to motorInstructionRate, which is used for waittime below
		driveRate	!? { motorInstructionRate = driveRate };

		// start the synth that forwards the driving control signal to the lang
		server ?? {server = Server.default};
		server.waitForBoot({

			if (followSynthXY.isNil) {
				var followDef;
				followDefXY = CtkSynthDef( \busFollowXY++id, {// make the def unique w/ id
					| followBusX, followBusY, sendRate|
					SendReply.ar(
						Impulse.ar(sendRate),
						'/busFollowXY'++id,
						[In.kr(followBusX, 1), In.kr(followBusY, 1)]
					)
				});
				server.sync;

				timeLastSent = Main.elapsedTime; // update start time

				followSynthXY = followDefXY.note
				.followBusX_(ctlBusX)
				.followBusY_(ctlBusY)
				.sendRate_(motorInstructionRate)
				.play
			} {
				timeLastSent = Main.elapsedTime; // update start time

				followSynthXY
				.followBusX_(ctlBusX)
				.followBusY_(ctlBusY)
				.sendRate_(motorInstructionRate)
				.run;
			};

			server.sync;

		});

		// Create a responder to receive the driving control signal position
		// from the server (SendReply from followSynthXY)

		// NOTE: could be replace by a routine calling ctlBusX/ctlBusY.getSyncronous
		// but getSyncronous has some truncation error or something...
		// see sc list and synchronous_bus_vs_OSC.scd
		followResponderXY ?? {

			followResponderXY = OSCFunc({ |msg, time, addr, recvPort|
				var goTo, maxNextDist, sent, catchup = 1;
				// var deltaFromWorld, deltaFromLast;
				// var nextPos, nextFeed;
				var nextTarget, nextFeed;
				// var droppedPrev = false;
				var now, dDelta, tDelta;

				if (mode != "Alarm") {
					var nextTarget;

					nextTarget = msg[3..4].asPoint; // "current" target position (bus value)

					// 	starving = (rxBuf.size < minMoveQueue);
					// 	this.changed(\starving, starving);
					//
					// 	// is it lagging too far behind the world?
					// 	lagging = (deltaFromWorld > maxLagDist);
					// 	this.changed(\lagging, lagging);

					now = Main.elapsedTime;
					tDelta = now - timeLastSent;

					dDelta = this.calcDistDelta(destLastSent, nextTarget);
					// dDelta = destLastSent.dist(nextTarget);

					nextFeed = dDelta / tDelta * 60;

					if (nextFeed > maxFeed) {
						// shorten the distance to match what can be covered at maxFeed
						var scaleDown;

						scaleDown = maxFeed / nextFeed;
						nextTarget = (nextTarget - destLastSent) * scaleDown + destLastSent;
						nextFeed = maxFeed;
					};

					if (pBuf.inRange(plannerMin, plannerMax).not)
					{	// planning buffer out of range

						if (pBuf < plannerMin)
						{	// planner is starving, slow down

							// "Planner starving, throttling down".warn;

							if (outOfRangeVal.isNil)
							{	// just crossed threshold, ramp up one level
								outOfRangeVal = pBuf;
								throttleCount = throttleCount+1;
								"...starving, slowing down.".postln; // debug
							} {
								if (pBuf < outOfRangeVal)
								{	// pBuf dropped yet another level
									throttleCount = throttleCount+1;
									outOfRangeVal = pBuf;
									"......slowing down more.".postln; // debug
								};
							};

							throttle = (1-autoThrottle).pow(throttleCount);

						} {	// (pBuf > plannerMax): planner count is too high, speed up

							// "Planner filling up, throttling up".warn;

							if (outOfRangeVal.isNil)
							{	// just crossed threshold, ramp down one level
								outOfRangeVal = pBuf;
								throttleCount = throttleCount-1;
								"...planner filling, speeding up.".postln; // debug
							} {
								if (pBuf > outOfRangeVal)
								{	// pBuf increased yet another level
									throttleCount = throttleCount-1;
									outOfRangeVal = pBuf;
									"......speeding up more.".postln; // debug
								};
							};

							throttle = (1+autoThrottle).pow(throttleCount.abs);
						};

						this.changed(\throttle, throttle);

					} {  // planner count is in range

						if (throttleCount != 0) {
							// was previously throttled
							if (throttleCount > 0)
							{	// was throttled down (was starving), speed back up
								throttleCount = throttleCount-1;
								throttle = (1-autoThrottle).pow(throttleCount);
								"speeding back up".postln; // debug

							} { // was throttled up (planner full), slow back down

								throttleCount = throttleCount+1;
								throttle = (1+autoThrottle).pow(throttleCount.abs);
								"slowing back down".postln; // debug
							};
							this.changed(\throttle, throttle);
						};

						outOfRangeVal = nil; // reset out-of-range trigger
						throttle = 1;
					};

					// postf("adjusted factor: %\n", throttle); // debug

					// TODO: consider slowing back down at a slower rate
					// to get in the middle of bounds
					// Also - preemptively throttle up when approaching the upper
					// bound of the safe planner range

					nextFeed = nextFeed * throttle; // user controlled throttle

					// minFeed is important when following a static value:
					// with little or no distance covered nextFeed can get very low,
					// which essentially backs up all the following instructions,
					// clogging the planning and rx buffer
					nextFeed = nextFeed.clip(minFeed,maxFeed);

					// TODO: account for lag caused by accelleration, so feed should be bumped up or something

					// this will only send if the serial rx buffer has enough room
					sent = this.submitMoveXY(
						nextTarget.x.round(0.001),
						nextTarget.y.round(0.001),
						nextFeed.round(0.001),
						now
					);
					this.changed(\sent, sent.asInt);

					// "feed ".post; nextFeed.round(0.001).postln; // debug

				} {
					"Won't follow in ALARM mode".postln;
				};

				// if (mode != "Alarm") {
				// 	// the target destination from the control sig
				// 	goTo = msg[3..4].asPoint;
				//
				// 	maxNextDist	= maxDistPerSec / motorInstructionRate;
				//
				// 	// distance from...
				// 	deltaFromWorld = goTo.dist(wPos[0..1].asPoint);	// the latest world position
				// 	deltaFromLast = goTo.dist(lastDest);					// the last scheduled destination
				//
				// 	// is the instruction buffer starving ?
				// 	starving = (rxBuf.size < minMoveQueue);
				// 	this.changed(\starving, starving);
				//
				// 	// is it lagging too far behind the world?
				// 	lagging = (deltaFromWorld > maxLagDist);
				// 	this.changed(\lagging, lagging);
				//
				// 	// is it trying to go farther than a maximum single drive instruction?
				// 	if (deltaFromLast > maxNextDist) {
				// 		// limit the next instruction to a max rate and distance
				// 		var difRatio;
				// 		difRatio = maxNextDist / deltaFromLast;
				//
				// 		nextPos = lastDest + (goTo - lastDest * difRatio);
				// 		nextFeed = maxFeed;
				// 		// debug.if{"Exceeding max move - limiting next destination".postln};
				// 	} {
				// 		if (droppedPrev)
				// 		{	// don't go as far because the last move was skipped
				// 			nextPos = lastDest + (goTo - lastDest * dropLag);
				// 		}{	// a regular destination and feed
				// 			nextPos = goTo;
				// 		};
				//
				// 		// nextFeed = (feedSpec.map(deltaFromLast/maxNextDist) * overDrive).clip(minFeed,maxFeed);
				// 		nextFeed = feedSpec.map(deltaFromLast/maxNextDist).clip(minFeed,maxFeed);
				//
				// 		// slow down if the instruction list is starving
				// 		if (starving) {
				// 			nextFeed = nextFeed * underDrive;
				// 		};
				//
				// 		if (lagging) {
				// 			// instruct to move further, bump up the next feed rate
				// 			// clipping at max feed
				// 			nextFeed = [
				// 				(nextFeed * overDrive),
				// 				maxFeed
				// 			].minItem;
				// 		};
				//
				// 		// if( lagging,
				// 		// 	{	// instruct to move further, bump up the next feed rate
				// 		// 		// clipping at max feed
				// 		// 		nextFeed = [
				// 		// 			(nextFeed * 1.2),
				// 		// 			maxFeed
				// 		// 		].minItem;
				// 		// 	},
				// 		// 	{	// slow down if the instruction list is starving
				// 		// 		if( starving, {
				// 		// 			nextFeed = nextFeed * underDrive;
				// 		// 			debug.if{"STARVING - under driving the feed".postln};
				// 		// 			} //, { catchup = 1 }
				// 		// 		);
				// 		// 	}
				// 		// );
				// 	};
				//
				// 	// try to send the move, returns false if no room in buffer
				// 	sent = this.submitMoveXY(
				// 		nextPos.x.round(0.001),
				// 		nextPos.y.round(0.001),
				// 		nextFeed.round(0.001)
				// 	);
				//
				// 	if (sent)
				// 	{
				// 		lastDest = nextPos;
				// 		this.changed(\sent, 1);
				// 		droppedPrev = false;
				// 	} {
				// 		// debug.if{ "skipping move".postln };
				// 		this.changed(\sent, 0);
				// 		droppedPrev = true;
				// 	};
				// }
				// { "won't follow in ALARM mode".postln };
			},
			// the OSC path;
			// TODO: could match args to followSynthXY's nodeID to avoid having to tag on ID
			'/busFollowXY'++id
			);
		};
	}

	calcDistDelta { |prevPnt, nextPnt|
		^prevPnt.dist(nextPnt);
	}

	// used by followSerialBufXY_
	submitMoveXY { |toX, toY, feed, when|
		var cmd, size, destX, destY;

		cmd = "G01";

		if ( toX.isNil or: toY.isNil ) {
			Error( format("x or y destination is nil! x: %  y: %\n", toX, toY) ).throw
		};

		#destX, destY = if (catchSoftLimit) {
			[this.checkBoundX(toX), this.checkBoundX(toY)]
		} {
			[toX, toY]
		};

		cmd = cmd ++ "X" ++ destX ++ "Y" ++ destY;
		feed !? {cmd = cmd ++ "F" ++ feed};
		size = cmd.size + 1; // add 1 for the eol char added in .send
		// postf("size: %\nrxBuf: %\nmaxRxBufsize: %\n\n", size , rxBuf, maxRxBufsize);

		// only send the move if the message won't overflow the serial buffer
		if ((size + rxBufsize) < maxRxBufsize) {
			this.send(cmd);
			timeLastSent = when ?? {Main.elapsedTime};
			destLastSent = Point(destX, destY);
			// "feed: ".post; feed.postln; // debug
			^true
		} {
			"RX buffer FULL! Not sending instruction".warn; // debug
			^false
		};
	}

	// Catch the move request before soft limit in GRBL to avoid ALARM
	checkBoundX { |toX|
		^if (toX.inRange(xClipLow, xClipHigh)) {
			toX
		} {
			warn("Clipping the requested X position!");
			toX.clip(xClipLow, xClipHigh);
		};
	}

	checkBoundY { |toY|
		^if (toY.inRange(yClipLow, yClipHigh)) {
			toY
		} {
			warn("clipping the requested Y position!");
			toY.clip(yClipLow, yClipHigh);
		};
	}

	// used by follow
	motorInstructionRate_ { |newRate|
		newRate !? {
			motorInstructionRate = newRate;
			followSynthXY !? {followSynthXY.sendRate_(motorInstructionRate)};
		}
	}

	minFeed_ { |feedRate|
		minFeed = feedRate;
		// feedSpec.minval_(minFeed);
		// feedSpec.default_(minFeed + (maxFeed-minFeed).half);
	}

	maxFeed_ { |feedRate|
		maxFeed = feedRate;
		// feedSpec.maxval_(maxFeed);
		// feedSpec.default_(minFeed + (maxFeed-minFeed).half);
	}


	unfollow {
		// need to nil this so it's recreated when follow is called
		// or else lastDest is not updated with the new value for some reason ???
		followResponderXY !? {
			followResponderXY.free;
			followResponderXY = nil;
		};
		followSynthXY !? {followSynthXY.pause};
		outOfRangeVal = nil;
		throttleCount = 0;
		// plannerView !? {plannerView.free};
	}

	// Go to a postion over specific duration.
	// overwrites Grbl.goToDur, clipping at min/maxFeed
	// NOTE: doesn't currently account for acceleration, so faster moves
	// will actually take a bit longer than expected
	goToDur_ { |toX, toY, duration|
		var dist, feedRateSec, feed;

		dist = Point(toX, toY).dist(Point(wPos[0], wPos[1]));

		// TODO: get this out of here - it's for coreXY movement ONLY
		// isolate it in DMDriver: better probably to scale the feed rate, not change the distance
		dist = dist * 2.sqrt;

		// // calculate the distance in terms of motor feed, not pen head movement
		// f = { |toX, toY, wPosX, wPosY|
		// 	var dX, dY, dA, dB, dist;
		// 	dX = toX - wPosX;
		// 	dY = toY - wPosY;
		// 	dA = dX+dY;
		// 	dB = dX-dY;
		//
		// 	// dist = hypot((0.5*(dA+dB)), (0.5*(dA-dB)));
		// 	dist = hypot(dA, dB);
		// 	[dA, dB];
		// 	[dist, Point(toX, toY).dist(Point(wPosX, wPosY))]
		// }
		//
		// f.(-5, -10, -25, -5)
		// f.(-5, -5, -15, -10)
		// f.(-5, -5, -5, -10)

		feedRateSec = dist / duration; // dist/sec
		feed = (feedRateSec * 60).clip(minFeed, maxFeed); // dist/min
		postf("goToDur feed: %\n", feed);
		this.goTo_( toX, toY, feed );
	}

	// To set bounds for both plotters and for catching moves that would
	// otherwise trigger soft limit ALARMs in GRBL
	// see catchSoftLimit arg in followSerialBufXY_ and submitMoveXY
	// TODO: could infer and/or update them from soft limit setting in GRBL
	xLimitLow_ { |lowBound|
		lowBound !? { xLimitLow = lowBound; this.updateSoftClipLimits }
	}
	xLimitHigh_ { |highBound|
		highBound !? { xLimitHigh = highBound; this.updateSoftClipLimits }
	}
	yLimitLow_ { |lowBound|
		lowBound !? { yLimitLow = lowBound; this.updateSoftClipLimits }
	}
	yLimitHigh_ { |highBound|
		highBound !? { yLimitHigh = highBound; this.updateSoftClipLimits }
	}

	softLimitsSC_ { |xLow,xHigh,yLow,yHigh|
		this.xLimitLow_(xLow); this.xLimitHigh_(xHigh);
		this.yLimitLow_(yLow); this.yLimitHigh_(yHigh);
	}

	updateSoftClipLimits {
		xClipLow = xLimitLow + softClipMargin;
		xClipHigh = xLimitHigh - softClipMargin;
		yClipLow = yLimitLow + softClipMargin;
		yClipHigh = yLimitHigh - softClipMargin;
	}

	// The margin padded on x and y limits to catch in SC
	// before sending instruction to grbl.
	// .catchSoftLimit_ must be set to true to clip the instruction.
	// This prevents soft limit ALARM state in grbl.
	softClipMargin_ { |margin|
		softClipMargin = margin;
		this.updateSoftClipLimits
	}


	free {
		this.unfollow;
		followSynthXY !? { followSynthXY.free };
		followResponderXY !? { followResponderXY.free };
		this.cleanupLfo;
		super.free;
	}
}