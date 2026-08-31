import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { HomeComponent } from './components/home/home.component';
import { PublicacionComponent } from './components/publicacion/publicacion.component';

const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'publicacion/:id', component: PublicacionComponent },
  { path: '**', redirectTo: '', pathMatch: 'full' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }