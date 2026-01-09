import Keycloak from 'keycloak-js';
const keycloak = new Keycloak({
    url: 'http://localhost:8081',
    realm: 'sotohp',
    clientId: 'sotohp-web',
});
export const initKeycloak = (onAuthenticatedCallback) => {
    keycloak
        .init({ onLoad: 'login-required', checkLoginIframe: false })
        .then((authenticated) => {
        if (authenticated) {
            onAuthenticatedCallback();
        }
        else {
            console.warn('Not authenticated!');
            keycloak.login();
        }
    })
        .catch(console.error);
};
export const getToken = () => keycloak.token;
export const updateToken = (minValidity = 5) => keycloak.updateToken(minValidity);
export const logout = () => keycloak.logout();
export const getUsername = () => keycloak.tokenParsed?.preferred_username || 'Unknown';
