import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import Keycloak from 'keycloak-js';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly keycloak = inject(Keycloak);

  protected logout(): void {
    void this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
