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
	println "Session: ${session.name} ${session.id}"
	print "Getting location data..."
	def locData = google.get(
		path: "/fitness/v1/users/me/dataSources/derived:com.google.location.sample:com.google.android.gms:merge_high_fidelity/datasets/${session.startTimeMillis.toLong() * 1000000}-${session.endTimeMillis.toLong() * 1000000}",
		headers: [ 'Authorization': "Bearer ${tokenResp.data.access_token}"]
	)
	println " Done"
	//println locData.data.point
	print "Getting distance data..."
	def distanceData = google.get(
		path: "/fitness/v1/users/me/dataSources/derived:com.google.distance.delta:com.google.android.gms:high_fidelity_from_activity<-derived:com.google.location.sample:com.google.android.gms:merge_high_fidelity/datasets/${session.startTimeMillis.toLong() * 1000000}-${session.endTimeMillis.toLong() * 1000000}",
		headers: [ 'Authorization': "Bearer ${tokenResp.data.access_token}"]
	)
	println " Done"
	//println distanceData.data.point
	print "Getting calories data..."
	def calories = google.get(
		path: "/fitness/v1/users/me/dataSources/derived:com.google.calories.expended:com.google.android.gms:from_activities/datasets/${session.startTimeMillis.toLong() * 1000000}-${session.endTimeMillis.toLong() * 1000000}",
		headers: [ 'Authorization': "Bearer ${tokenResp.data.access_token}"]
	)
	println " Done"
	//println calories.data.point[0].value[0].fpVal
	def activityType
	switch(session.activityType) {
		case 1:
			activityType = 'Biking'
			break
		case 8:
			activityType = 'Running'
			break
		case 64:
			activityType = 'Inline skating'
			break
		default:
			activityType = 'Unknown'
			break
	}
	def fileNameDate = new Date(session.startTimeMillis.toLong()).format("yyyy-MM-dd'T'HH_mm_ssZ")
	def gmtDate = new Date(session.startTimeMillis.toLong()).format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('GMT'))
	def xml = new MarkupBuilder(new FileWriter("${fileNameDate}_${activityType.replaceAll(' ', '_')}.tcx"))
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
			Activity(Sport: activityType) {
				Id(gmtDate)
				Lap(StartTime: gmtDate) {
					TotalTimeSeconds((session.endTimeMillis.toLong() - session.startTimeMillis.toLong()) / 1000)
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
	//break
}

