import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouteReuseStrategy } from '@angular/router';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HomeComponent } from './components/home/home.component';
import { PublicacionComponent } from './components/publicacion/publicacion.component';
import { ModoToggleComponent } from './components/modo-toggle/modo-toggle.component';
import { TiraContextoComponent } from './components/tira-contexto/tira-contexto.component';
import { AppRouteReuseStrategy } from './app-route-reuse.strategy';

@NgModule({
  declarations: [
    AppComponent,
    HomeComponent,
    PublicacionComponent,
    ModoToggleComponent,
    TiraContextoComponent
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule,
    HttpClientModule,
    FormsModule,
    ReactiveFormsModule,
    MatSnackBarModule,
    MatProgressBarModule
  ],
  providers: [
    { provide: RouteReuseStrategy, useClass: AppRouteReuseStrategy }
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
