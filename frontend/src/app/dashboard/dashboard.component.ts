import { Component, viewChild } from '@angular/core';

import { XrayStatusListComponent } from '../xray/status-list/xray-status-list.component';
import { XrayUploadComponent } from '../xray/upload/xray-upload.component';
import { XrayRequest } from '../xray/xray-request.model';

/**
 * The app's single authenticated route (Step 5.5): batch upload + live status list, wired so
 * a successful upload immediately seeds the status list's rows instead of waiting for the
 * first SSE event to arrive.
 */
@Component({
  selector: 'app-dashboard',
  imports: [XrayUploadComponent, XrayStatusListComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  private readonly statusList = viewChild.required(XrayStatusListComponent);

  protected onUploaded(created: XrayRequest[]): void {
    this.statusList().upsertMany(created);
  }
}
