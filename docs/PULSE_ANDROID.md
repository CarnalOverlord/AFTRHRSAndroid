# Android Pulse integration notes

Pulse is implemented as a native Compose feature opened from The Grid toolbar. It uses the authenticated shared Ktor/OkHttp client and cookie jar, with a dedicated `/api/pulse/` request path.

Grid profile badges use the nullable `pulse` projection returned alongside each member by the existing bulk `GET /api/v1/discovery` response. The client intentionally does not issue a Pulse request per profile card or alter discovery ordering. The chip is rendered only when the backend supplies a recognized primary intention and an unexpired `expiresAt`; missing, hidden, unsupported, or expired snapshots display no badge. The projection is controlled by backend discovery eligibility and visibility rules.

Profile detail renders the backend's existing nullable `pulse` projection and accepted `partners` list. Other-member Pulse visibility and eligibility are enforced by the profile API before the snapshot is returned. The signed-in member's own profile loads `/api/pulse/me` once because the public profile route intentionally omits the viewer's Pulse. No Pulse requests are made per Grid card. The Pulse profile panel is informational, since the profile projection does not include the UUID required by the newer detail/response endpoints.

The response API and UI use the normal direct-conversation Inbox route. Requested conversations are not accepted automatically. FCM remains on the existing `MESSAGE` notification route and deduplicates display by backend `notificationId`; realtime Inbox refresh behavior continues to use the app's existing lifecycle refresh.

The contract and backend implementation used for this integration are feature-branch sources. The production API origin was not probed or assumed to support Pulse. Backend migrations and deployment, authenticated-device integration, and push delivery remain separate verification steps.
