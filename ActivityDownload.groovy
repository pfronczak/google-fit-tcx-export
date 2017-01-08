@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')

import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*
import groovy.xml.MarkupBuilder
import groovy.json.JsonBuilder

def google = new RESTClient('https://www.googleapis.com')

print "Logging in to Google API..."
def tokenResp = google.post(
	path: '/oauth2/v4/token',
	body: [
		// See: https://developers.google.com/fit/rest/v1/get-started
		client_id: 'OAUTH_CLIENT_ID',
		client_secret: 'OAUTH_CLIENT_SECRET',
		grant_type: 'refresh_token', 
		refresh_token: 'OAUTH_REFRESH_TOKEN'
	],
	requestContentType: URLENC
)
println " Done. Access token: ${tokenResp.data.access_token}"

print "Getting sessions..."
def query = [:];
if (args.size() > 0) {
	query['startTime'] = args[0];
}
if (args.size() > 1) {
	query['pageToken'] = args[1];
}
def sessions = google.get(
	path: '/fitness/v1/users/me/sessions',
	query: query,
	headers: [ 'Authorization': "Bearer ${tokenResp.data.access_token}"]
)
println " Done. Next page token: ${sessions.data.nextPageToken}"

for (session in sessions.data.session) {
	if (!session.name) continue;
	println "Session: ${session.name} ${session.id}"
	saveSession(google, tokenResp.data.access_token, session.activityType, session.startTimeMillis.toLong() * 1000000, session.endTimeMillis.toLong() * 1000000, true);
}

print "Getting auto-detected activities..."
def nowNanos = new Date().getTime() * 1000000;
def lastWeekNanos = new Date().minus(7).getTime() * 1000000;
def segments = google.get(
	path: "/fitness/v1/users/me/dataSources/derived:com.google.activity.segment:com.google.android.gms:merge_activity_segments/datasets/$lastWeekNanos-$nowNanos",
	headers: [ 'Authorization': "Bearer ${tokenResp.data.access_token}"]
)
println " Done."

for (segment in segments.data.point) {
	if (segment.originDataSourceId.endsWith('session_activity_segment')) continue;
	if ((segment.endTimeNanos.toLong() - segment.startTimeNanos.toLong()) / 1000000000 < 60) continue;
	def activityType = segment.value[0].intVal;
	if (activityType == 1 || activityType == 8) {
		def startDt = new Date(segment.startTimeNanos.toLong().intdiv(1000000L)).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
		println "Segment: ${activityType == 1 ? 'Biking' : 'Running'} ${startDt}"
		saveSession(google, tokenResp.data.access_token, activityType, segment.startTimeNanos.toLong(), segment.endTimeNanos.toLong(), false);
	}
}

void saveSession(def google, String accessToken, int activityType, long startTimeNanos, long endTimeNanos, boolean accurate) {
	print "Getting location data..."
	def locDataSource = 'derived:com.google.location.sample:com.google.android.gms:merge_high_fidelity';
	if (!accurate) {
		locDataSource = 'derived:com.google.location.sample:com.google.android.gms:merge_location_samples';
	}
	def locData = google.get(
		path: "/fitness/v1/users/me/dataSources/$locDataSource/datasets/${startTimeNanos}-${endTimeNanos}",
		headers: [ 'Authorization': "Bearer ${accessToken}"]
	)
	println " Done"
	//println locData.data.point
	if (!locData.data.point) {
		println "-- skipped --";
		return;
	}
	print "Getting distance data..."
	def distDataSource = 'derived:com.google.distance.delta:com.google.android.gms:high_fidelity_from_activity<-derived:com.google.location.sample:com.google.android.gms:merge_high_fidelity';
	if (!accurate) {
		distDataSource = 'derived:com.google.distance.delta:com.google.android.gms:merge_distance_delta';
	}
	def distanceData = google.get(
		path: "/fitness/v1/users/me/dataSources/$distDataSource/datasets/${startTimeNanos}-${endTimeNanos}",
		headers: [ 'Authorization': "Bearer ${accessToken}"]
	)
	println " Done"
	//println distanceData.data.point
	if (!distanceData.data.point) {
		println "-- skipped --";
		return;
	}
	print "Getting calories data..."
	def calories = google.get(
		path: "/fitness/v1/users/me/dataSources/derived:com.google.calories.expended:com.google.android.gms:from_activities/datasets/${startTimeNanos}-${endTimeNanos}",
		headers: [ 'Authorization': "Bearer ${accessToken}"]
	)
	println " Done"
	//println calories.data.point[0].value[0].fpVal
	def activityTypeName
	switch(activityType) {
		case 1:
			activityTypeName = 'Biking'
			break
		case 8:
			activityTypeName = 'Running'
			break
		case 64:
			activityTypeName = 'Inline skating'
			break
		case 67:
			activityTypeName = 'Cross-country skiing'
			break
		default:
			activityTypeName = 'Unknown'
			break
	}
	def fileNameDate = new Date(startTimeNanos.intdiv(1000000L)).format("yyyy-MM-dd'T'HH_mm_ssZ")
	def gmtDate = new Date(startTimeNanos.intdiv(1000000L)).format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('GMT'))
	def xml = new MarkupBuilder(new FileWriter("${fileNameDate}_${activityTypeName.replaceAll(' ', '_')}.tcx"))
	xml.doubleQuotes = true
	xml.mkp.xmlDeclaration(version: "1.0", encoding: "utf-8")
	xml.TrainingCenterDatabase(
		'xmlns': 'http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2',
		'xmlns:ns2': 'http://www.garmin.com/xmlschemas/UserProfile/v2',
		'xmlns:ns3': 'http://www.garmin.com/xmlschemas/ActivityExtension/v2',
		'xmlns:ns4': 'http://www.garmin.com/xmlschemas/ProfileExtension/v1',
		'xmlns:ns5': 'http://www.garmin.com/xmlschemas/ActivityGoals/v1',
		'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance',
		'xsi:schemaLocation': 'http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd',
	) {
		Activities {
			Activity(Sport: activityTypeName) {
				Id(gmtDate)
				Lap(StartTime: gmtDate) {
					TotalTimeSeconds((endTimeNanos - startTimeNanos) / 1000000000)
					DistanceMeters(distanceData.data.point*.value*.fpVal.sum().sum())
					Calories(calories.data.point[0].value[0].fpVal)
					Intensity('Active')
					TriggerMethod('Manual')
					Track {
						locData.data.point.each { p ->
							Trackpoint {
								Time(new Date(p.startTimeNanos.toLong().intdiv(1000000L)).format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('GMT')))
								Position {
									LatitudeDegrees(p.value[0].fpVal)
									LongitudeDegrees(p.value[1].fpVal)
								}
								if (p.value.size() == 4) {
									AltitudeMeters(p.value[3].fpVal)
								}
							}
						}
					}
				}
			}
		}
		Author('xsi:type': 'Application_t') {
			Name('Google Fit')
			Build {
				Version {
					VersionMajor('0')
					VersionMinor('0')
					BuildMajor('0')
					BuildMinor('0')
				}
			}
			LangID('en')
			PartNumber('000-00000-00')
		}
	}
}