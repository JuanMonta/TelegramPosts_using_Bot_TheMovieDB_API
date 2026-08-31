import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ApiService {
  readonly baseUrl = environment.apiUrl;

  constructor(private http: HttpClient) {}

  get<T>(path: string): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}${path}`).pipe(catchError((e) => this.fallar(e)));
  }

  post<T>(path: string, body?: unknown): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body ?? null).pipe(catchError((e) => this.fallar(e)));
  }

  put<T>(path: string, body?: unknown): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body ?? null).pipe(catchError((e) => this.fallar(e)));
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${path}`).pipe(catchError((e) => this.fallar(e)));
  }

  /** Extrae el mensaje amigable (campo "error") o una descripción técnica. */
  mensajeDeError(e: HttpErrorResponse): string {
    const body = e.error;
    if (body && typeof body === 'object' && typeof body.error === 'string') {
      return body.error;
    }
    if (e.status === 0) {
      return 'No se pudo conectar al backend (puerto 9000).';
    }
    return `Error ${e.status ?? ''} al llamar al backend.`.trim();
  }

  private fallar<T>(e: HttpErrorResponse): Observable<never> {
    return throwError(() => e);
  }
}